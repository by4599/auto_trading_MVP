package com.trading;

import com.trading.market.MarketCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 텔레그램 봇 알림 — 체결/에러 이벤트를 운영자에게 전달한다.
 *
 * bot-token이 비어있으면 로그만 남기고 스킵 (로컬/테스트 환경 graceful degradation).
 * send()에서 발생한 예외는 절대 매매 흐름으로 전파하지 않는다.
 */
@Component
public class TelegramNotifier implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final TelegramProperties props;
    private final MarketCalendarService marketCalendar;
    private final RestClient restClient;

    public TelegramNotifier(TelegramProperties props, MarketCalendarService marketCalendar) {
        this.props = props;
        this.marketCalendar = marketCalendar;
        // 네트워크 장애 시 빠른 실패: 알림 실패는 무시하도록 설계되어 있으므로
        // 타임아웃 없이 블로킹되는 것보다 3+5초 안에 실패하는 게 안전하다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Override
    public void sendCritical(String message) {
        send(message);
    }

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
        try {
            restClient.post()
                    .uri("https://api.telegram.org/bot" + props.getBotToken() + "/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", props.getChatId(), "text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 알림 실패는 매매 흐름에 영향을 주지 않는다
            log.error("Telegram 알림 전송 실패: {}", text, e);
        }
    }
}
