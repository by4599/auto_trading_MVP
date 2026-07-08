package com.trading.position;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PositionManager 모의투자 구현체.
 * Position 테이블을 읽어 Account 스냅샷을 만든다.
 *
 * TODO Sprint 3:
 *   - dailyPnlPercent: 오늘 체결된 SELL 주문의 실현손익 / 시작 자산 으로 계산
 *   - consecutiveLossCount: order_history의 최근 SELL 체결 결과 연속 손실 카운트
 *   - totalAssetValue: KIS 잔고조회 API (VTTC8434R)로 실제 평가금액 조회
 *   - currentPrice: 현재가 API로 교체 (현재는 averagePrice로 근사)
 */
@Service
@Profile("paper")
public class KisPositionManager implements PositionManager {

    private static final Logger log = LoggerFactory.getLogger(KisPositionManager.class);

    private final PositionRepository positionRepository;

    public KisPositionManager(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    /**
     * [운영 주의] DailyLossRule, ConsecutiveLossRule은 현재 비활성 상태.
     * dailyPnlPercent = 0.0, consecutiveLossCount = 0 고정값이 반환되므로
     * 두 룰의 차단 조건이 실질적으로 동작하지 않는다.
     * Sprint 3에서 KIS 잔고조회 API + order_history 집계로 구현 예정.
     */
    @PostConstruct
    void warnInactiveRules() {
        log.warn("[운영 주의] DailyLossRule / ConsecutiveLossRule 비활성 상태 — Sprint 3 구현 전까지 해당 리스크 룰은 작동하지 않습니다");
    }

    @Override
    public Account snapshotAccount() {
        List<Position> positions = positionRepository.findAll();

        List<Account.PositionSnapshot> snapshots = positions.stream()
                .map(p -> new Account.PositionSnapshot(
                        p.getStockCode(),
                        p.getQuantity(),
                        p.getAveragePrice(),
                        p.getAveragePrice()  // TODO Sprint 3: 현재가 API로 교체
                ))
                .toList();

        double totalAssetValue = snapshots.stream()
                .mapToDouble(Account.PositionSnapshot::marketValue)
                .sum();

        log.debug("계좌 스냅샷: 보유종목={} 평가금액={}", snapshots.size(), totalAssetValue);

        return new Account(
                totalAssetValue,
                0.0,    // TODO Sprint 3: 당일 실현손익률 계산
                0,      // TODO Sprint 3: 연속 손실 횟수 계산
                snapshots
        );
    }
}
