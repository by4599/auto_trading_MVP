package com.trading;

import com.trading.market.MarketCalendarService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 텔레그램 봇 알림 — 체결/에러 이벤트를 운영자에게 전달한다.
 *
 * bot-token이 비어있으면 로그만 남기고 스킵 (로컬/테스트 환경 graceful degradation).
 * send()에서 발생한 예외는 절대 매매 흐름으로 전파하지 않는다.
 *
 * 전송은 호출자를 막지 않는다 (2026-10-10, BACKLOG [2026-09-21]):
 *   호출부 대부분이 @Scheduled 기본 스레드 1개 위의 감시({@code RiskMonitor}·{@code ShadowPortfolioReconciler})
 *   이거나 그 스레드에서 발행된 체결 이벤트라, 동기 HTTP(최대 8초) 동안 손절·강제청산 감시가 멈췄다.
 *   이제 판정(토큰·장중 창)만 호출 스레드에서 하고, HTTP는 전용 단일 스레드 대기열
 *   ({@link BackgroundAlertSender}, 스레드 이름 telegram-sender)이 받은 순서대로 보낸다.
 *   장중 창은 <b>알림을 받은 시각</b> 기준이다 — 15:29:59에 받은 건 15:30을 넘겨 전달돼도 나간다.
 */
@Component
public class TelegramNotifier implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    /**
     * 대기열 상한 — 평시 몰림(타임컷·강제청산 때 수 건~십수 건)보다 넉넉하고, 텔레그램이 죽어 한 건에
     * 8초씩 걸릴 때도 메모리는 지키는 선. 넘친 건은 본문째 WARN 로그로만 남는다.
     */
    static final int QUEUE_CAPACITY = 100;

    private final TelegramProperties props;
    private final MarketCalendarService marketCalendar;
    private final BackgroundAlertSender sender;

    @Autowired
    public TelegramNotifier(TelegramProperties props, MarketCalendarService marketCalendar) {
        this(props, marketCalendar, httpPoster(props));
    }

    /** 테스트 전용 — 실제 HTTP 대신 가짜 전송기를 끼운다 */
    TelegramNotifier(TelegramProperties props, MarketCalendarService marketCalendar, Consumer<String> poster) {
        this.props = props;
        this.marketCalendar = marketCalendar;
        this.sender = new BackgroundAlertSender(text -> post(poster, text), "telegram-sender", QUEUE_CAPACITY);
    }

    private static Consumer<String> httpPoster(TelegramProperties props) {
        // 네트워크 장애 시 빠른 실패: 알림 실패는 무시하도록 설계되어 있으므로
        // 타임아웃 없이 블로킹되는 것보다 3+5초 안에 실패하는 게 안전하다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        RestClient restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
        return text -> restClient.post()
                .uri("https://api.telegram.org/bot" + props.getBotToken() + "/sendMessage")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("chat_id", props.getChatId(), "text", text))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void sendCritical(String message) {
        send(message);
    }

    /** 즉시 반환한다 — 보낼 대상이면 대기열에 넣고, 실제 HTTP는 telegram-sender 스레드가 순서대로 보낸다 */
    public void send(String text) {
        if (props.getBotToken().isBlank()) {
            log.debug("Telegram 미설정 — 알림 스킵: {}", text);
            return;
        }
        // 장중~장마감(거래일 09:00~15:30)에만 텔레그램 전송 — 장외 이벤트는 로그로만 남긴다
        if (!marketCalendar.isDuringMarketHoursNow()) {
            log.info("장외 시간 — 텔레그램 알림 스킵(로그만): {}", text);
            return;
        }
        sender.send(text);
    }

    /** telegram-sender 스레드에서 돈다 */
    private static void post(Consumer<String> poster, String text) {
        try {
            poster.accept(text);
        } catch (Exception e) {
            // 알림 실패는 매매 흐름에 영향을 주지 않는다
            log.error("Telegram 알림 전송 실패: {}", text, e);
        }
    }

    /** 앱 종료 시 — 새 알림은 막고, 받아 둔 것은 최대 3초 동안 마저 보낸다 */
    @PreDestroy
    void shutdownSender() {
        sender.close();
    }
}
