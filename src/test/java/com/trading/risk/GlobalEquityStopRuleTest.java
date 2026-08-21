package com.trading.risk;

import com.trading.position.Account;
import com.trading.position.NoOpPeakEquityCalibrator;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.PositionManager;
import com.trading.position.ShadowPortfolio;
import com.trading.signal.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MDD 매수 게이트 — 전고점이 '미검증'(클램프 교정값)이어도 <b>매수 차단은 유지</b>된다.
 *
 * <p>2026-08-21 감사 후속: 미검증 상태에서 보류되는 것은 RiskMonitor의 자동 강제청산뿐이다.
 * 진입까지 같이 풀리면 보류가 위험을 늘리게 되므로 이 룰의 거부는 그대로 둔다.
 *
 * <p>Java 25 목킹 제약 — ShadowPortfolio는 실객체이고 미검증 여부만 익명 하위 클래스로 주입한다.
 */
@DisplayName("GlobalEquityStopRule — MDD 매수 게이트")
class GlobalEquityStopRuleTest {

    private PositionManager positionManager;

    @BeforeEach
    void setUp() {
        positionManager = mock(PositionManager.class);
    }

    private ShadowPortfolio portfolioWithPeak(double peak, boolean unverified) {
        ShadowPortfolio portfolio = new ShadowPortfolio(positionManager,
                mock(PortfolioStateRepository.class), new NoOpPeakEquityCalibrator()) {
            @Override
            public boolean isPeakUnverified() {
                return unverified;
            }
        };
        when(positionManager.snapshotAccount()).thenReturn(account(peak));
        portfolio.tick();
        assertThat(portfolio.getPeakEquity()).isEqualTo(peak);
        return portfolio;
    }

    private static Account account(double totalAsset) {
        return new Account(totalAsset, 0.0, 0, List.of());
    }

    private static RiskResult check(ShadowPortfolio portfolio, double currentAsset) {
        return new GlobalEquityStopRule(portfolio, new RiskLimitsProperties())
                .validate(Signal.buy("005930", "TEST"), account(currentAsset));
    }

    @Test
    @DisplayName("전고점이 미검증이어도 MDD 한도 초과면 매수를 막는다")
    void rejects_even_when_peak_unverified() {
        // 전고점 1,150만(클램프 상한) · 현재 1,000만 = MDD 13.04% > 10%
        RiskResult result = check(portfolioWithPeak(11_500_000, true), 10_000_000);

        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("미검증");   // 보류 사실을 사유에 알린다
    }

    @Test
    @DisplayName("검증된 전고점이면 사유에 보류 문구가 붙지 않는다 (기존 문구 유지)")
    void verified_peak_keeps_original_reason() {
        RiskResult result = check(portfolioWithPeak(11_500_000, false), 10_000_000);

        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).doesNotContain("미검증");
    }

    @Test
    @DisplayName("MDD 한도 안이면 미검증이어도 통과시킨다 (과차단 금지)")
    void passes_within_limit() {
        RiskResult result = check(portfolioWithPeak(11_500_000, true), 11_000_000);

        assertThat(result.isPass()).isTrue();
    }
}
