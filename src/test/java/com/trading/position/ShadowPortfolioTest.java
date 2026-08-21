package com.trading.position;

import com.trading.NotificationService;
import com.trading.risk.RiskLimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * peakEquity(전고점) 오염 방지·교정 회귀 테스트.
 *
 * 2026-08-12 사고: portfolio_state.PEAK_EQUITY = 13,027,929원이 저장돼 있었으나
 * daily_equity 24거래일 이력의 MAX(start_equity)는 10,000,000원(시드 자본)이었다.
 * 가짜 전고점 때문에 MDD -23%로 오판 → 신규 매수가 영구 거부됐다.
 *
 * Java 25 Mockito 제약: 구체 클래스(ShadowPortfolio·EvidenceBasedPeakEquityCalibrator)는
 * 목킹하지 않고 인터페이스(PositionManager·PortfolioStateRepository·DailyEquityRepository·
 * NotificationService)만 목으로 조립한다.
 */
@DisplayName("ShadowPortfolio — 전고점 오염 차단 / 실측 클램프")
class ShadowPortfolioTest {

    private static final double POLLUTED_PEAK = 13_027_929.0;   // 실제 오염값
    private static final double VERIFIED_MAX  = 10_000_000.0;   // daily_equity MAX(start_equity)
    private static final double CEILING       = 11_500_000.0;   // 기본 파라미터(0.10×5종목=50% 노출 → +15%)

    private PositionManager positionManager;
    private PortfolioStateRepository stateRepository;
    private DailyEquityRepository dailyEquityRepository;
    private NotificationService notifier;
    private RiskLimitsProperties limits;

    @BeforeEach
    void setUp() {
        positionManager       = mock(PositionManager.class);
        stateRepository       = mock(PortfolioStateRepository.class);
        dailyEquityRepository = mock(DailyEquityRepository.class);
        notifier              = mock(NotificationService.class);
        limits                = new RiskLimitsProperties();   // 기본값: 비중 10%, 최대 5종목
        when(dailyEquityRepository.findMaxStartEquity()).thenReturn(VERIFIED_MAX);
    }

    private EvidenceBasedPeakEquityCalibrator calibrator() {
        return new EvidenceBasedPeakEquityCalibrator(
                dailyEquityRepository, stateRepository, limits, notifier);
    }

    private ShadowPortfolio sut() {
        return new ShadowPortfolio(positionManager, stateRepository, calibrator());
    }

    private static Account account(double totalAsset) {
        return new Account(totalAsset, 0.0, 0, List.of());
    }

    private void storedPeak(double value) {
        when(stateRepository.findById(PortfolioState.KEY_PEAK_EQUITY))
                .thenReturn(Optional.of(PortfolioState.of(PortfolioState.KEY_PEAK_EQUITY, value)));
    }

    // ── tick(): 오염 차단 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("낡은(폴백) 스냅샷은 전고점을 갱신하지 못한다")
    void stale_snapshot_does_not_raise_peak() {
        when(positionManager.snapshotAccount()).thenReturn(account(POLLUTED_PEAK).asStale());

        ShadowPortfolio sut = sut();
        sut.tick();

        assertThat(sut.getPeakEquity()).isZero();
        verify(stateRepository, never()).save(any());
    }

    @Test
    @DisplayName("신선하지만 실측 근거상 불가능한 총자산도 전고점을 갱신하지 못한다")
    void implausible_fresh_snapshot_does_not_raise_peak() {
        when(positionManager.snapshotAccount()).thenReturn(account(POLLUTED_PEAK));

        ShadowPortfolio sut = sut();
        sut.tick();

        assertThat(sut.getPeakEquity()).isZero();
        verify(stateRepository, never()).save(any());
    }

    @Test
    @DisplayName("신선하고 근거 범위 안인 총자산은 전고점을 갱신·영속화한다")
    void fresh_plausible_snapshot_raises_peak() {
        when(positionManager.snapshotAccount()).thenReturn(account(10_500_000));

        ShadowPortfolio sut = sut();
        sut.tick();

        assertThat(sut.getPeakEquity()).isEqualTo(10_500_000);
        ArgumentCaptor<PortfolioState> saved = ArgumentCaptor.forClass(PortfolioState.class);
        verify(stateRepository).save(saved.capture());
        assertThat(saved.getValue().getStateKey()).isEqualTo(PortfolioState.KEY_PEAK_EQUITY);
        assertThat(saved.getValue().getStateValue()).isEqualTo(10_500_000);
    }

    @Test
    @DisplayName("총자산 0(폴백 근사)은 전고점을 갱신하지 않는다")
    void zero_asset_does_not_raise_peak() {
        when(positionManager.snapshotAccount()).thenReturn(account(0));

        ShadowPortfolio sut = sut();
        sut.tick();

        assertThat(sut.getPeakEquity()).isZero();
        verify(stateRepository, never()).save(any());
    }

    @Test
    @DisplayName("실측 기록이 없으면 검증을 보류하고 기존대로 갱신한다")
    void without_evidence_peak_updates_as_before() {
        when(dailyEquityRepository.findMaxStartEquity()).thenReturn(null);
        when(positionManager.snapshotAccount()).thenReturn(account(50_000_000));

        ShadowPortfolio sut = sut();
        sut.tick();

        assertThat(sut.getPeakEquity()).isEqualTo(50_000_000);
    }

    // ── 경계값 (감사 LOW: 정확히 상한 위/아래) ─────────────────────────────────

    @Nested
    @DisplayName("상한 경계")
    class CeilingBoundary {

        @Test
        @DisplayName("정확히 상한(1.15배)은 정상으로 보고 갱신한다")
        void exactly_ceiling_is_plausible() {
            when(positionManager.snapshotAccount()).thenReturn(account(CEILING));

            ShadowPortfolio sut = sut();
            sut.tick();

            assertThat(sut.getPeakEquity()).isEqualTo(CEILING);
        }

        @Test
        @DisplayName("상한을 1원이라도 넘으면 갱신하지 않는다")
        void one_won_above_ceiling_is_implausible() {
            when(positionManager.snapshotAccount()).thenReturn(account(CEILING + 1));

            ShadowPortfolio sut = sut();
            sut.tick();

            assertThat(sut.getPeakEquity()).isZero();
        }

        @Test
        @DisplayName("저장값이 정확히 상한이면 교정하지 않는다")
        void restore_at_ceiling_is_untouched() {
            storedPeak(CEILING);

            ShadowPortfolio sut = sut();
            sut.restore();

            assertThat(sut.getPeakEquity()).isEqualTo(CEILING);
            verify(stateRepository, never()).save(any());
        }
    }

    // ── 리스크 파라미터 결합 (감사 HIGH-2) ─────────────────────────────────────

    @Nested
    @DisplayName("허용폭은 RiskLimitsProperties에서 유도한다")
    class HeadroomDerivation {

        @Test
        @DisplayName("기본값(비중 10% × 5종목 = 노출 50%)이면 허용폭 15%")
        void default_parameters_give_15_percent() {
            assertThat(calibrator().intradayHeadroom()).isEqualTo(0.15);
            assertThat(calibrator().isImplausible(CEILING + 1)).isTrue();
        }

        @Test
        @DisplayName("비중을 30%로 올리면 허용폭도 따라 올라 정당한 신고점을 막지 않는다")
        void wider_parameters_widen_headroom() {
            limits.setMaxPositionWeight(0.30);   // 0.30 × 5종목 = 노출 100%(상한) → 허용폭 30%

            assertThat(calibrator().intradayHeadroom()).isEqualTo(0.30);
            // 기본 파라미터였다면 불가능(>1,150만)으로 막혔을 값이 이제는 정상
            assertThat(calibrator().isImplausible(12_000_000)).isFalse();
            assertThat(calibrator().isImplausible(13_500_000)).isTrue();
        }

        @Test
        @DisplayName("노출은 100%를 넘지 못한다 — 허용폭 상한은 30%")
        void exposure_is_capped_at_one() {
            limits.setMaxPositionWeight(0.30);
            limits.setMaxPositionCount(20);      // 0.30 × 20 = 6.0 → min(1.0)

            assertThat(calibrator().intradayHeadroom()).isEqualTo(0.30);
        }
    }

    // ── restore(): 오염 클램프 (감사 HIGH-1) ───────────────────────────────────

    @Test
    @DisplayName("기동 시 오염된 전고점을 '검증된 최대 자산'이 아니라 상한까지만 낮춘다")
    void restore_clamps_to_ceiling_not_verified_max() {
        storedPeak(POLLUTED_PEAK);

        ShadowPortfolio sut = sut();
        sut.restore();

        assertThat(sut.getPeakEquity()).isEqualTo(CEILING);
        assertThat(sut.getPeakEquity()).isGreaterThan(VERIFIED_MAX);   // 과잉 하향 금지
    }

    @Test
    @DisplayName("클램프 시 원값을 별도 키로 보존하고 교정값을 영속화한다")
    void restore_preserves_raw_value_and_persists_clamped() {
        storedPeak(POLLUTED_PEAK);

        ShadowPortfolio sut = sut();
        sut.restore();

        ArgumentCaptor<PortfolioState> saved = ArgumentCaptor.forClass(PortfolioState.class);
        // 원값 보존 + 미검증 표시 + 교정값 영속화 3건
        verify(stateRepository, org.mockito.Mockito.times(3)).save(saved.capture());

        PortfolioState raw = saved.getAllValues().stream()
                .filter(s -> PortfolioState.KEY_PEAK_EQUITY_RAW_BEFORE_CALIBRATION.equals(s.getStateKey()))
                .findFirst().orElseThrow();
        assertThat(raw.getStateValue()).isEqualTo(POLLUTED_PEAK);

        PortfolioState peak = saved.getAllValues().stream()
                .filter(s -> PortfolioState.KEY_PEAK_EQUITY.equals(s.getStateKey()))
                .findFirst().orElseThrow();
        assertThat(peak.getStateValue()).isEqualTo(CEILING);
    }

    @Test
    @DisplayName("클램프가 발생하면 텔레그램(sendCritical)으로도 알린다")
    void restore_notifies_on_clamp() {
        storedPeak(POLLUTED_PEAK);

        sut().restore();

        verify(notifier).sendCritical(contains("전고점 교정"));
    }

    @Test
    @DisplayName("근거 범위 안의 전고점은 그대로 복원하고 알리지도 않는다")
    void restore_keeps_plausible_peak() {
        storedPeak(11_000_000.0);

        ShadowPortfolio sut = sut();
        sut.restore();

        assertThat(sut.getPeakEquity()).isEqualTo(11_000_000.0);
        verify(stateRepository, never()).save(any());
        verify(notifier, never()).sendCritical(anyString());
    }

    @Test
    @DisplayName("실측 기록이 없으면 저장된 전고점을 임의로 건드리지 않는다")
    void restore_without_evidence_keeps_stored_peak() {
        when(dailyEquityRepository.findMaxStartEquity()).thenReturn(null);
        storedPeak(POLLUTED_PEAK);

        ShadowPortfolio sut = sut();
        sut.restore();

        assertThat(sut.getPeakEquity()).isEqualTo(POLLUTED_PEAK);
        verify(stateRepository, never()).save(any());
    }

    // ── 클램프 이후: MDD 자동청산 보류 표시 (감사 2026-08-21 MEDIUM) ────────────

    @Nested
    @DisplayName("클램프하면 전고점을 '미검증'으로 표시한다 — 자동 강제청산만 보류")
    class UnverifiedMark {

        @Test
        @DisplayName("클램프가 일어나면 미검증으로 표시하고 그 사실을 영속화한다")
        void clamp_marks_peak_unverified() {
            storedPeak(POLLUTED_PEAK);

            ShadowPortfolio sut = sut();
            sut.restore();

            assertThat(sut.isPeakUnverified()).isTrue();
            ArgumentCaptor<PortfolioState> saved = ArgumentCaptor.forClass(PortfolioState.class);
            verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
            PortfolioState mark = saved.getAllValues().stream()
                    .filter(s -> PortfolioState.KEY_PEAK_EQUITY_UNVERIFIED.equals(s.getStateKey()))
                    .findFirst().orElseThrow();
            assertThat(mark.getStateValue()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("클램프된 상한으로도 MDD가 한도(10%)를 넘는다 — 보류가 필요한 이유")
        void clamped_ceiling_still_exceeds_mdd_limit() {
            storedPeak(POLLUTED_PEAK);

            ShadowPortfolio sut = sut();
            sut.restore();

            double drawdownAtCeiling = (sut.getPeakEquity() - VERIFIED_MAX) / sut.getPeakEquity();
            assertThat(drawdownAtCeiling).isGreaterThan(0.10);   // 13.04% — 자동청산이 그대로 돌면 헛청산
            assertThat(sut.isPeakUnverified()).isTrue();
        }

        @Test
        @DisplayName("클램프가 없으면 미검증 표시도 없다")
        void no_clamp_no_mark() {
            storedPeak(11_000_000.0);

            ShadowPortfolio sut = sut();
            sut.restore();

            assertThat(sut.isPeakUnverified()).isFalse();
        }

        @Test
        @DisplayName("재시작해도 표시가 유지된다 — 껐다 켜는 것으로 자동청산이 되살아나지 않는다")
        void mark_survives_restart() {
            when(stateRepository.findById(PortfolioState.KEY_PEAK_EQUITY_UNVERIFIED))
                    .thenReturn(Optional.of(PortfolioState.of(
                            PortfolioState.KEY_PEAK_EQUITY_UNVERIFIED, 1)));
            storedPeak(CEILING);   // 이미 클램프된 값이 저장돼 있어 이번 기동엔 교정이 안 일어난다

            ShadowPortfolio sut = sut();
            sut.restore();

            assertThat(sut.getPeakEquity()).isEqualTo(CEILING);
            assertThat(sut.isPeakUnverified()).isTrue();
        }

        @Test
        @DisplayName("사람이 확인하면 표시를 지우고 그 사실도 영속화한다")
        void acknowledge_clears_mark() {
            storedPeak(POLLUTED_PEAK);
            ShadowPortfolio sut = sut();
            sut.restore();

            assertThat(sut.acknowledgePeakEquity()).isTrue();

            assertThat(sut.isPeakUnverified()).isFalse();
            ArgumentCaptor<PortfolioState> saved = ArgumentCaptor.forClass(PortfolioState.class);
            verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
            assertThat(saved.getAllValues().stream()
                    .filter(s -> PortfolioState.KEY_PEAK_EQUITY_UNVERIFIED.equals(s.getStateKey()))
                    .map(PortfolioState::getStateValue).toList())
                    .containsExactly(1.0, 0.0);   // 표시 → 해제 순
            assertThat(sut.getPeakEquity()).isEqualTo(CEILING);   // 확인은 값을 바꾸지 않는다
        }

        @Test
        @DisplayName("보류가 없는데 확인하면 아무 일도 하지 않는다")
        void acknowledge_without_mark_is_noop() {
            storedPeak(11_000_000.0);
            ShadowPortfolio sut = sut();
            sut.restore();

            assertThat(sut.acknowledgePeakEquity()).isFalse();
            verify(stateRepository, never()).save(any());
        }
    }

    // ── 사고의 최종 증상: 운영 DB에 반영한 실측 교정값이 매수 게이트를 연다 ──────

    @Test
    @DisplayName("운영 DB에 넣은 1,000만원 전고점이면 현재 자산 999만은 MDD 한도 10% 안이다")
    void corrected_db_value_reopens_buy_gate() {
        storedPeak(VERIFIED_MAX);   // 2026-08-12 1회성 DB 교정으로 실제 저장된 값

        ShadowPortfolio sut = sut();
        sut.restore();

        double current = 9_992_814.0;   // 같은 날 daily_equity 시작 자산 실측값
        double drawdownPolluted = (POLLUTED_PEAK - current) / POLLUTED_PEAK;
        double drawdownNow      = (sut.getPeakEquity() - current) / sut.getPeakEquity();

        assertThat(sut.getPeakEquity()).isEqualTo(VERIFIED_MAX);   // 상한 이하라 재교정 없음
        assertThat(drawdownPolluted).isGreaterThan(0.10);          // 오염 상태 = 매수 영구 거부
        assertThat(drawdownNow).isLessThan(0.10);                  // 교정 후 = 정상
    }

    // ── 백테스트 결정성 (감사 MEDIUM-1) ────────────────────────────────────────

    @Nested
    @DisplayName("백테스트 구현체는 검증하지 않는다 — G0 앵커 재현성 보존")
    class BacktestCalibrator {

        @Test
        @DisplayName("저장값을 그대로 두고, 어떤 자산도 비정상으로 보지 않는다")
        void noop_calibrator_changes_nothing() {
            PeakEquityCalibrator noop = new NoOpPeakEquityCalibrator();

            assertThat(noop.calibrate(POLLUTED_PEAK)).isEqualTo(POLLUTED_PEAK);
            assertThat(noop.isImplausible(Double.MAX_VALUE)).isFalse();
        }

        @Test
        @DisplayName("무검증 구현체로 조립하면 tick 갱신 동작이 변경 전과 같다")
        void backtest_tick_behaviour_unchanged() {
            when(positionManager.snapshotAccount()).thenReturn(account(50_000_000));
            ShadowPortfolio sut = new ShadowPortfolio(
                    positionManager, stateRepository, new NoOpPeakEquityCalibrator());

            sut.tick();

            assertThat(sut.getPeakEquity()).isEqualTo(50_000_000);
        }
    }
}
