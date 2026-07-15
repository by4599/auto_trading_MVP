package com.trading;

import com.trading.position.PositionRepository;
import com.trading.risk.TradingStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 데드맨 스위치 (OPERATIONS §2) — 앱이 죽으면 알림도 같이 죽으므로,
 * "부재 감지"는 반드시 외부 서비스(healthchecks.io 등)가 담당해야 한다.
 * 이 컴포넌트는 5분마다 외부 URL로 ping을 보내기만 한다 — 외부 서비스가
 * 일정 시간(예: 10분) 이상 ping이 없으면 경고를 보내는 쪽을 설정해야 완성된다.
 *
 * ping 실패는 TelegramNotifier와 같은 원칙으로 매매 흐름에 전파하지 않는다.
 */
@Component
@Profile("paper")
public class DeadmanHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(DeadmanHeartbeat.class);

    private final HeartbeatProperties props;
    private final TradingStatusManager statusManager;
    private final PositionRepository positionRepository;
    private final RestClient restClient;

    @Autowired
    public DeadmanHeartbeat(HeartbeatProperties props,
                             TradingStatusManager statusManager,
                             PositionRepository positionRepository) {
        this(props, statusManager, positionRepository, defaultClient());
    }

    /** 테스트 전용 — MockRestServiceServer로 바인딩한 RestClient를 직접 주입한다 */
    DeadmanHeartbeat(HeartbeatProperties props,
                      TradingStatusManager statusManager,
                      PositionRepository positionRepository,
                      RestClient restClient) {
        this.props = props;
        this.statusManager = statusManager;
        this.positionRepository = positionRepository;
        this.restClient = restClient;
    }

    private static RestClient defaultClient() {
        // 네트워크 장애 시 빠른 실패: 5분 주기 스케줄이 밀리지 않도록 타임아웃을 짧게 둔다
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedRate = 300_000) // 5분마다
    public void ping() {
        if (!props.isConfigured()) {
            log.debug("[Heartbeat] URL 미설정 — 스킵");
            return;
        }
        try {
            restClient.post()
                    .uri(props.getUrl())
                    .body(heartbeatPayload())
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
}
