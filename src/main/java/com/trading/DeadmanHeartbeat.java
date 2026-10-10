package com.trading;

import com.trading.position.PositionRepository;
import com.trading.risk.TradingStatusManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 데드맨 스위치 (OPERATIONS §2) — 앱이 죽으면 알림도 같이 죽으므로,
 * "부재 감지"는 반드시 외부 서비스(healthchecks.io 등)가 담당해야 한다.
 * 이 컴포넌트는 5분마다 외부 URL로 ping을 보내기만 한다 — 외부 서비스가
 * 일정 시간(예: 10분) 이상 ping이 없으면 경고를 보내는 쪽을 설정해야 완성된다.
 *
 * ping 실패는 TelegramNotifier와 같은 원칙으로 매매 흐름에 전파하지 않는다.
 *
 * 스레드 배치 (2026-10-10, BACKLOG [2026-09-21]): 박동 트리거는 일부러 <b>기본 스케줄러 스레드</b>
 * (손절·강제청산 감시와 같은 스레드)에 남기고, 느린 HTTP만 <b>전용 전송 스레드</b>(heartbeat-sender)로 넘긴다.
 * 트리거까지 옮기면 감시 스레드가 멈춰도 박동은 계속 나가 외부 감시가 침묵을 못 본다 —
 * 장애 대응(OPERATIONS §2: 경고 → 수동 손절선 예약)이 필요한 바로 그 상황을 놓친다.
 * 전송을 뉴스·공시 수집과 같은 I/O 풀에 두면 피드가 매달릴 때 박동이 밀려 매매는 멀쩡한데 경고가 울린다(42_audit M-1).
 */
@Component
@Profile("paper")
public class DeadmanHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(DeadmanHeartbeat.class);

    private final HeartbeatProperties props;
    private final TradingStatusManager statusManager;
    private final PositionRepository positionRepository;
    private final RestClient restClient;
    private final Executor sendExecutor;

    @Autowired
    public DeadmanHeartbeat(HeartbeatProperties props,
                             TradingStatusManager statusManager,
                             PositionRepository positionRepository) {
        this(props, statusManager, positionRepository, defaultClient(), dedicatedSender());
    }

    /** 테스트 전용 — MockRestServiceServer로 바인딩한 RestClient와 실행기를 직접 주입한다 */
    DeadmanHeartbeat(HeartbeatProperties props,
                      TradingStatusManager statusManager,
                      PositionRepository positionRepository,
                      RestClient restClient,
                      Executor sendExecutor) {
        this.props = props;
        this.statusManager = statusManager;
        this.positionRepository = positionRepository;
        this.restClient = restClient;
        this.sendExecutor = sendExecutor;
    }

    private static RestClient defaultClient() {
        // 네트워크 장애 시 빠른 실패: 5분 주기 스케줄이 밀리지 않도록 타임아웃을 짧게 둔다
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** 스레드 1개 + 대기 2건 — 밀려도 쌓이지 않게 가장 오래된 박동부터 버린다(낡은 박동은 의미가 없다) */
    private static ExecutorService dedicatedSender() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2),
                r -> {
                    Thread t = new Thread(r, "heartbeat-sender");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /** 기본 스케줄러 스레드에서 박동 내용을 만들고, HTTP 전송은 전용 스레드로 넘긴 뒤 바로 돌아온다 */
    @Scheduled(fixedRate = 300_000) // 5분마다
    public void ping() {
        if (!props.isConfigured()) {
            log.debug("[Heartbeat] URL 미설정 — 스킵");
            return;
        }
        try {
            String payload = heartbeatPayload();
            sendExecutor.execute(() -> post(payload));
        } catch (Exception e) {
            // 내용 조립 실패·실행기 거부(종료 중) 모두 박동 없음으로 끝낸다 (외부 감시가 침묵으로 감지)
            log.warn("[Heartbeat] 전송 준비 실패 — {}", e.getMessage());
        }
    }

    /** heartbeat-sender 스레드에서 돈다 */
    private void post(String payload) {
        try {
            restClient.post()
                    .uri(props.getUrl())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 하트비트 실패는 매매 흐름에 영향을 주지 않는다 (외부 감시가 침묵으로 감지)
            log.warn("[Heartbeat] 전송 실패 — {}", e.getMessage());
        }
    }

    /** 경고 수신 시 "지금 시장에 노출된 게 있는가"를 폰에서 바로 판단할 수 있도록 포함 (OPERATIONS §2) */
    String heartbeatPayload() {
        long heldPositions = positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .count();
        return String.format("mode=%s positions=%d", statusManager.getCurrentMode(), heldPositions);
    }

    /** 앱 종료 시 — 스스로 만든 전송 스레드만 정리한다(테스트가 넣어 준 실행기는 건드리지 않는다) */
    @PreDestroy
    void shutdownSender() {
        if (sendExecutor instanceof ExecutorService owned) owned.shutdownNow();
    }
}
