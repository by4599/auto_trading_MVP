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
