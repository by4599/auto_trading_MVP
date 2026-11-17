package com.trading.dashboard;

import com.trading.position.Account;
import com.trading.position.DailyEquity;
import com.trading.position.DailyEquityRepository;
import com.trading.position.NoOpPeakEquityCalibrator;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.PositionManager;
import com.trading.position.ShadowPortfolio;
import com.trading.risk.RiskLimitsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 계좌 기준 성적 API — "얼마 벌었나"의 정본을 daily_equity로 옮긴 것.
 *
 * <p>trade_result는 모의 매도 체결가 결함(CLAUDE.md 결함 5)으로 손익이 과대계상된다
 * (실적 탭이 -5,014,000원을 보여줬으나 실제는 -212,040원). daily_equity는 하루의 시작·마감
 * 총자산이라 수수료·세금이 이미 반영돼 있고 그 결함의 영향을 받지 않는다.
 *
 * <p>Java 25 Mockito 제약: 리포지토리(인터페이스)만 목으로 만들고 ShadowPortfolio는
 * 실객체로 조립한다. 날짜 사실관계(시스템 도구로 검증): 2026-09-19 토 · 09-20 일 · 09-21 월.
 */
@DisplayName("AccountPerformanceService — 계좌 잔고 원장 기준 성적")
class AccountPerformanceServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate MON_0921 = LocalDate.of(2026, 9, 21);

    /** 2026-09-21 운영 DB 실측값 — 이 셋이 이 API의 검산 기준이다 */
    private static final double SEED    = 10_000_000.0;   // 2026-07-16 최초 기록
    private static final double PEAK    = 10_088_806.0;   // portfolio_state.PEAK_EQUITY (09-01 실측 최고)
    private static final double CURRENT =  9_787_960.0;   // 09-21 총자산

    private final DailyEquityRepository equityRepo = mock(DailyEquityRepository.class);
    private final RiskLimitsProperties limits = new RiskLimitsProperties();   // 기본 mddLimit 0.10
    private final Clock clock = Clock.fixed(MON_0921.atTime(22, 30).atZone(KST).toInstant(), KST);

    // ── 조립 헬퍼 ─────────────────────────────────────────────────────────────

    /** 전고점만 세팅된 실객체 ShadowPortfolio — 목은 전부 인터페이스다 */
    private static ShadowPortfolio shadowWithPeak(double peak) {
        PositionManager positionManager = mock(PositionManager.class);
        when(positionManager.snapshotAccount()).thenReturn(new Account(peak, 0.0, 0, List.of()));
        ShadowPortfolio sp = new ShadowPortfolio(
                positionManager, mock(PortfolioStateRepository.class), new NoOpPeakEquityCalibrator());
        sp.tick();
        return sp;
    }

    private AccountPerformanceService sut(double peak) {
        return new AccountPerformanceService(equityRepo, shadowWithPeak(peak), limits, clock);
    }

    /** 리포지토리는 최신 날짜가 먼저 나온다 — 서비스가 뒤집어 쓰는지 함께 고정한다 */
    private void givenLedger(DailyEquity... rows) {
        List<DailyEquity> desc = new ArrayList<>(List.of(rows));
        desc.sort(Comparator.comparing(DailyEquity::getTradeDate).reversed());
        when(equityRepo.findByTradeDateGreaterThanEqualOrderByTradeDateDesc(any(LocalDate.class)))
                .thenReturn(desc);
    }

    private static DailyEquity open(String date, double startEquity) {
        return DailyEquity.of(LocalDate.parse(date), startEquity);
    }

    private static DailyEquity closed(String date, double startEquity, double endEquity) {
        DailyEquity d = DailyEquity.of(LocalDate.parse(date), startEquity, startEquity);
        d.recordClose(endEquity, endEquity);
        return d;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    /** 2026-07-16 ~ 09-21 운영 원장의 축약본 (주말 행 포함 — 실제로 들어 있다) */
    private void givenRealLedgerShape() {
        givenLedger(
                open("2026-07-16", SEED),
                open("2026-07-30",  9_912_930),
                open("2026-08-29", 10_029_976),   // 토요일 행
                open("2026-09-01", PEAK),
                open("2026-09-07",  9_869_651),
                open("2026-09-11", CURRENT),
                open("2026-09-19", CURRENT),      // 토요일 행
                open("2026-09-20", CURRENT),      // 일요일 행
                closed("2026-09-21", CURRENT, CURRENT));
    }

    // ── 검산: 실제 계좌 숫자 ───────────────────────────────────────────────────

    @Test
    @DisplayName("누적 손익은 -212,040원 — 실적 탭이 보여주던 -5,014,000원이 아니다")
    void cumulative_pnl_matches_real_account() {
        givenRealLedgerShape();

        Map<String, Object> out = sut(PEAK).accountPerformance(365);

        assertThat(out.get("initialEquity")).isEqualTo(10_000_000L);
        assertThat(out.get("currentEquity")).isEqualTo(9_787_960L);
        assertThat(out.get("cumulativePnl")).isEqualTo(-212_040L);
        assertThat(out.get("cumulativeReturnPercent")).isEqualTo(-2.12);
    }

    @Test
    @DisplayName("현재 낙폭·강제정지 문턱·남은 금액이 운영 실측과 일치한다")
    void drawdown_and_threshold_match_real_account() {
        givenRealLedgerShape();

        Map<String, Object> out = sut(PEAK).accountPerformance(365);

        assertThat(out.get("peakEquity")).isEqualTo(10_088_806L);
        assertThat(out.get("currentDrawdownPercent")).isEqualTo(-2.98);
        assertThat(out.get("forcedStopThreshold")).isEqualTo(9_079_925L);   // 전고점 × (1 - 0.10)
        assertThat(out.get("roomToThreshold")).isEqualTo(708_035L);
        assertThat(out.get("peakUnverified")).isEqualTo(false);
    }

    @Test
    @DisplayName("전고점은 ShadowPortfolio 값을 쓴다 — 원장 최고가 아니라 안전장치가 보는 값")
    void peak_comes_from_shadow_portfolio_not_ledger() {
        givenLedger(open("2026-09-01", 10_000_000), closed("2026-09-21", 9_800_000, 9_800_000));

        Map<String, Object> out = sut(12_000_000).accountPerformance(365);

        assertThat(out.get("peakEquity")).isEqualTo(12_000_000L);
        assertThat(out.get("currentDrawdownPercent")).isEqualTo(-18.33);   // (9.8M-12M)/12M
    }

    @Test
    @DisplayName("문턱은 risk.mddLimit(설정 UI와 같은 출처)에서 나온다")
    void threshold_follows_risk_limits_properties() {
        givenLedger(closed("2026-09-21", CURRENT, CURRENT));
        limits.setMddLimit(0.20);

        Map<String, Object> out = sut(10_000_000).accountPerformance(30);

        assertThat(out.get("mddLimitPercent")).isEqualTo(20.0);
        assertThat(out.get("forcedStopThreshold")).isEqualTo(8_000_000L);
    }

    // ── MDD (최대 낙폭) ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("MDD — 시계열의 러닝 최대 대비 최대 하락폭")
    class MaxDrawdown {

        @Test
        @DisplayName("계속 오르기만 하면 낙폭은 0이다")
        void only_rising_gives_zero() {
            givenLedger(open("2026-09-01", 10_000_000),
                        open("2026-09-02", 10_500_000),
                        closed("2026-09-03", 11_000_000, 11_000_000));

            Map<String, Object> out = sut(11_000_000).accountPerformance(30);

            assertThat(out.get("maxDrawdownPercent")).isEqualTo(0.0);
            assertThat(out.get("currentDrawdownPercent")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("중간에 꺼졌다 회복해도 가장 깊었던 골짜기를 기억한다")
        void dip_then_recovery_keeps_deepest_valley() {
            givenLedger(open("2026-09-01", 10_000_000),
                        open("2026-09-02", 12_000_000),                  // 러닝 최대
                        open("2026-09-03",  9_000_000),                  // -25% — 가장 깊은 골짜기
                        closed("2026-09-04", 13_000_000, 13_000_000));   // 신고점으로 회복

            Map<String, Object> out = sut(13_000_000).accountPerformance(30);

            assertThat(out.get("maxDrawdownPercent")).isEqualTo(-25.0);
            assertThat(out.get("currentDrawdownPercent")).isEqualTo(0.0);   // 지금은 회복 상태
        }

        @Test
        @DisplayName("주말 행이 섞여도 낙폭은 흔들리지 않고, 손익 0인 거래일로 세지도 않는다")
        void weekend_rows_do_not_distort() {
            givenLedger(open("2026-09-17", 12_000_000),                   // 목 — 러닝 최대
                        open("2026-09-18",  9_000_000),                   // 금 — -25%
                        open("2026-09-19",  9_000_000),                   // 토 (주말 행, 실제로 들어 있다)
                        open("2026-09-20",  9_000_000),                   // 일
                        closed("2026-09-21", 9_000_000, 9_000_000));      // 월

            Map<String, Object> out = sut(12_000_000).accountPerformance(30);

            assertThat(out.get("maxDrawdownPercent")).isEqualTo(-25.0);
            assertThat(out.get("closedDays")).isEqualTo(1L);   // 주말 2행은 마감 기록이 없다
            assertThat(out.get("netPnlSum")).isEqualTo(0L);
            assertThat(list(out, "series")).hasSize(5);        // 시계열은 값이 있는 날을 전부 그린다
        }
    }

    // ── 마감 기록 유무 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("마감을 못 찍은 날은 손익을 0이 아니라 null로 두고 합계에서 뺀다")
    void unclosed_day_is_null_not_zero() {
        givenLedger(closed("2026-09-17", 10_000_000, 9_900_000),   // -100,000
                    open("2026-09-18", 9_900_000));                 // 마감 기록 없음

        Map<String, Object> out = sut(10_000_000).accountPerformance(30);
        List<Map<String, Object>> daily = list(out, "daily");

        assertThat(daily).hasSize(2);
        assertThat(daily.get(0).get("date")).isEqualTo("2026-09-18");   // 최신이 먼저
        assertThat(daily.get(0).get("closed")).isEqualTo(false);
        assertThat(daily.get(0).get("netPnl")).isNull();
        assertThat(daily.get(1).get("netPnl")).isEqualTo(-100_000L);
        assertThat(out.get("netPnlSum")).isEqualTo(-100_000L);
        assertThat(out.get("closedDays")).isEqualTo(1L);
    }

    @Test
    @DisplayName("마감을 찍은 날은 시계열이 마감 총자산을 쓴다")
    void closed_day_series_uses_end_equity() {
        givenLedger(closed("2026-09-21", 10_000_000, 9_800_000));

        Map<String, Object> out = sut(10_000_000).accountPerformance(30);

        assertThat(list(out, "series")).singleElement()
                .satisfies(p -> assertThat(p.get("equity")).isEqualTo(9_800_000L));
        assertThat(out.get("currentEquity")).isEqualTo(9_800_000L);
    }

    // ── 경계 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("기록이 없으면 0으로 꾸미지 않고 null을 돌려준다")
    void empty_ledger_returns_nulls() {
        givenLedger();

        Map<String, Object> out = sut(PEAK).accountPerformance(30);

        assertThat(out.get("initialEquity")).isNull();
        assertThat(out.get("currentEquity")).isNull();
        assertThat(out.get("cumulativePnl")).isNull();
        assertThat(out.get("cumulativeReturnPercent")).isNull();
        assertThat(out.get("maxDrawdownPercent")).isNull();
        assertThat(list(out, "series")).isEmpty();
        assertThat(list(out, "daily")).isEmpty();
    }

    @Test
    @DisplayName("전고점이 아직 없으면(0) 낙폭·문턱은 null이다")
    void no_peak_yields_null_drawdown() {
        givenLedger(closed("2026-09-21", CURRENT, CURRENT));

        Map<String, Object> out = sut(0).accountPerformance(30);

        assertThat(out.get("peakEquity")).isNull();
        assertThat(out.get("currentDrawdownPercent")).isNull();
        assertThat(out.get("forcedStopThreshold")).isNull();
        assertThat(out.get("roomToThreshold")).isNull();
    }

    @Test
    @DisplayName("days는 1~3650으로 제한된다")
    void days_parameter_is_bounded() {
        givenLedger();

        assertThat(sut(PEAK).accountPerformance(0).get("days")).isEqualTo(1);
        assertThat(sut(PEAK).accountPerformance(99_999).get("days")).isEqualTo(3650);
        assertThat(sut(PEAK).accountPerformance(90).get("days")).isEqualTo(90);
    }

    @Test
    @DisplayName("응답은 출처를 밝힌다 — trade_result와 헷갈리지 않게")
    void response_declares_its_source() {
        givenLedger();

        assertThat((String) sut(PEAK).accountPerformance(30).get("source"))
                .contains("daily_equity");
    }
}
