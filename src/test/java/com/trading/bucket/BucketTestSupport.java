package com.trading.bucket;

import com.trading.position.PositionRepository;
import com.trading.position.TradeResultRepository;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 칸 실험과 무관한 테스트용 고정 픽스처 — 칸 나누기 OFF (기존 사이징 동작 그대로).
 */
public final class BucketTestSupport {

    private BucketTestSupport() {}

    /**
     * 오버라이드 없는 칸 파라미터 리졸버 — 전 항목이 전역값으로 폴백한다.
     * 즉 칸별 분리 도입 이전과 동일한 값이 나오므로 기존 테스트의 기대값이 바뀌지 않는다.
     */
    public static BucketParameterResolver defaultParams() {
        return defaultParams(new com.trading.risk.RiskLimitsProperties(),
                new com.trading.strategy.FilterProperties());
    }

    /** 전역 홀더를 지정해 만드는 리졸버 — 테스트가 전역값을 바꿔가며 검증할 때 쓴다 */
    public static BucketParameterResolver defaultParams(
            com.trading.risk.RiskLimitsProperties limits,
            com.trading.strategy.FilterProperties filters) {
        return new BucketParameterResolver(new BucketParameters(), limits, filters);
    }

    public static BucketProperties disabledProps() {
        return new BucketProperties(false, "2026-07-20",
                10_000_000, 10_000_000, 10_000_000, false, false);
    }

    public static BucketAccountService disabledAccounts() {
        PositionRepository positions = mock(PositionRepository.class);
        TradeResultRepository trades = mock(TradeResultRepository.class);
        when(positions.findAll()).thenReturn(List.of());
        when(trades.findAll()).thenReturn(List.of());
        return new BucketAccountService(disabledProps(), positions, trades);
    }
}
