package com.trading.strategy;

import com.trading.market.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 매집 흔적 신호 — 사전 등록 정의(`_workspace/51_quant` §0.3)를 숫자로 못 박는다.
 *
 * <p>시리즈 규약: 마지막 봉이 신호일 t. 기본 시리즈는 종가 100에서 봉마다 +0.05 오르는 완만한 상승
 * (추세 조건 통과), 고가=종가+0.5 · 저가=종가−0.5 · 거래량 1000.
 */
@DisplayName("AccumulationSignalCalculator — 매집 흔적 3가족 + 데이터 관문 + 추세 조건")
class AccumulationSignalCalculatorTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private static Candle bar(int i, double close, long volume) {
        return new Candle(START.plusDays(i), close, close + 0.5, close - 0.5, close, volume);
    }

    /** 완만한 상승 n봉 — 종가(t) > 직전 120봉 평균 */
    private static List<Candle> rising(int n) {
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) bars.add(bar(i, 100 + 0.05 * i, 1000));
        return bars;
    }

    /** 평평한 n봉 (종가 100) — 추세 조건이 없는 NT 규칙 검사용 */
    private static List<Candle> flat(int n) {
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) bars.add(bar(i, 100, 1000));
        return bars;
    }

    /** 끝에서 back번째 봉(0 = t)의 거래량만 바꾼다 */
    private static void setVolume(List<Candle> bars, int back, long volume) {
        int i = bars.size() - 1 - back;
        Candle c = bars.get(i);
        bars.set(i, new Candle(c.date(), c.open(), c.high(), c.low(), c.close(), volume));
    }

    /** 끝에서 back번째 봉의 종가를 바꾼다 (고가·저가는 종가 ±0.5로 함께 이동) */
    private static void setClose(List<Candle> bars, int back, double close) {
        int i = bars.size() - 1 - back;
        bars.set(i, bar(i, close, bars.get(i).volume()));
    }

    private static double closeAt(List<Candle> bars, int back) {
        return bars.get(bars.size() - 1 - back).close();
    }

    private static final AccumulationRule A3 = AccumulationRule.quietSpike(3.0);
    private static final AccumulationRule A3_NT = A3.withoutTrend();

    // ── A. 조용한 거래량 급증 ────────────────────────────────────────────────

    @Nested
    @DisplayName("A. 조용한 거래량 급증")
    class QuietSpike {

        @Test
        @DisplayName("거래량(t)이 직전 20봉 평균의 정확히 3배이고 등락이 작으면 발화한다 (평균에 t 자신은 넣지 않는다)")
        void firesAtExactlyKTimesPriorAverage() {
            List<Candle> bars = rising(121);
            setVolume(bars, 0, 3000); // t를 평균에 넣으면 (19×1000+3000)/20=1100 → 2.73배라 발화하지 않는다

            assertThat(AccumulationSignalCalculator.fires(bars, A3)).isTrue();
        }

        @Test
        @DisplayName("2.99배면 발화하지 않는다")
        void doesNotFireBelowK() {
            List<Candle> bars = rising(121);
            setVolume(bars, 0, 2990);

            assertThat(AccumulationSignalCalculator.fires(bars, A3)).isFalse();
        }

        @Test
        @DisplayName("평균 창은 직전 20봉뿐이다 — t−21의 거대 거래량은 평균에 들어가지 않는다")
        void averageWindowIsExactlyTwentyPriorBars() {
            List<Candle> bars = rising(121);
            setVolume(bars, 21, 1_000_000);
            setVolume(bars, 0, 3000);

            assertThat(AccumulationSignalCalculator.fires(bars, A3)).isTrue();
        }

        @Test
        @DisplayName("등락률 +3.5%(장대양봉)면 발화하지 않는다")
        void bigUpCandleIsNotQuiet() {
            List<Candle> bars = rising(121);
            setClose(bars, 0, closeAt(bars, 1) * 1.035);
            setVolume(bars, 0, 5000);

            assertThat(AccumulationSignalCalculator.fires(bars, A3)).isFalse();
        }

        @Test
        @DisplayName("등락률 −3.5%(투매)도 발화하지 않는다 — |등락률| ≤ 3% 양쪽 한도(사전 등록 §0.3)")
        void bigDownCandleIsNotQuietEither() {
            List<Candle> bars = flat(121);
            setClose(bars, 0, 96.5);
            setVolume(bars, 0, 5000);

            assertThat(AccumulationSignalCalculator.fires(bars, A3_NT)).isFalse();
        }

        @Test
        @DisplayName("등락률 정확히 +3.00%는 한도 안이다 (부동소수 오차로 떨어뜨리지 않는다)")
        void exactlyThreePercentIsInclusive() {
            List<Candle> bars = flat(121);
            setClose(bars, 0, 103);
            setVolume(bars, 0, 3000);

            assertThat(AccumulationSignalCalculator.fires(bars, A3_NT)).isTrue();
        }
    }

    // ── 공통 데이터 관문 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("공통 데이터 관문 — 최근 31봉(t−30..t)")
    class DataGuard {

        @Test
        @DisplayName("t−25에 거래량 0(거래정지)이 있으면 신호 없음 — A의 평균 창(20봉) 밖이어도 막는다")
        void zeroVolumeInsideGuardBlocks() {
            List<Candle> bars = flat(40);
            setVolume(bars, 25, 0);
            setVolume(bars, 0, 3000);

            assertThat(AccumulationSignalCalculator.fires(bars, A3_NT)).isFalse();
        }

        @Test
        @DisplayName("t−31의 거래량 0은 관문 밖이라 막지 않는다")
        void zeroVolumeOutsideGuardIsIgnored() {
            List<Candle> bars = flat(40);
            setVolume(bars, 31, 0);
            setVolume(bars, 0, 3000);

            assertThat(AccumulationSignalCalculator.fires(bars, A3_NT)).isTrue();
        }

        @Test
        @DisplayName("액면분할(−80% 갭 + 거래량 5배)이 창 안에 있으면 신호 없음 — 관문이 없으면 3.75배로 발화했을 상황")
        void splitDiscontinuityBlocks() {
            List<Candle> bars = flat(60);
            for (int back = 15; back >= 0; back--) {
                setClose(bars, back, 20);
                setVolume(bars, back, 5000);
            }
            setVolume(bars, 0, 15_000); // 평균 (5×1000+15×5000)/20 = 4000 → 3.75배

            assertThat(AccumulationSignalCalculator.fires(bars, A3_NT)).isFalse();
        }

        @Test
        @DisplayName("진짜 상한가(+30%)는 정상 움직임이라 관문을 통과한다")
        void genuineLimitUpPasses() {
            List<Candle> bars = flat(60);
            for (int back = 15; back >= 0; back--) setClose(bars, back, 130);
            setVolume(bars, 0, 3000);

            assertThat(AccumulationSignalCalculator.fires(bars, A3_NT)).isTrue();
        }
    }

    // ── 종목 추세 조건 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("종목 추세 조건 — 종가(t) > 직전 120봉 평균")
    class TrendCondition {

        @Test
        @DisplayName("하락 추세면 추세 조건이 막고, NT 규칙은 같은 봉에서 발화한다")
        void downtrendBlocksOnlyWhenTrendFilterOn() {
            List<Candle> bars = new ArrayList<>();
            for (int i = 0; i < 121; i++) bars.add(bar(i, 120 - 0.05 * i, 1000));
            setVolume(bars, 0, 3000);

            assertThat(AccumulationSignalCalculator.fires(bars, A3)).isFalse();
            assertThat(AccumulationSignalCalculator.fires(bars, A3_NT)).isTrue();
        }

        @Test
        @DisplayName("추세 조건이 있으면 완성 봉 121개가 필요하다 (120개면 판정하지 않는다)")
        void trendNeedsHundredTwentyOneBars() {
            List<Candle> short120 = rising(120);
            setVolume(short120, 0, 3000);
            List<Candle> full121 = rising(121);
            setVolume(full121, 0, 3000);

            assertThat(AccumulationSignalCalculator.fires(short120, A3)).isFalse();
            assertThat(AccumulationSignalCalculator.fires(full121, A3)).isTrue();
        }

        @Test
        @DisplayName("NT 규칙은 관문 길이(31봉)만 있으면 된다 (30봉이면 판정하지 않는다)")
        void noTrendNeedsGuardBarsOnly() {
            List<Candle> short30 = flat(30);
            setVolume(short30, 0, 3000);
            List<Candle> full31 = flat(31);
            setVolume(full31, 0, 3000);

            assertThat(AccumulationSignalCalculator.fires(short30, A3_NT)).isFalse();
            assertThat(AccumulationSignalCalculator.fires(full31, A3_NT)).isTrue();
        }
    }

    // ── B. 매집일 누적 + 바닥 다지기 ─────────────────────────────────────────

    @Nested
    @DisplayName("B. 매집일 누적 + 바닥 다지기")
    class ClusterBase {

        private final AccumulationRule b3x11 = AccumulationRule.clusterBase(3, 0.11);

        @Test
        @DisplayName("최근 10일 중 매집일(거래량 1.5배·|등락| ≤ 2%)이 정확히 3일이고 고저폭이 좁으면 발화한다")
        void firesWithExactlyMDaysAndTightRange() {
            List<Candle> bars = rising(121);
            setVolume(bars, 9, 2000);
            setVolume(bars, 5, 2000);
            setVolume(bars, 0, 2000);

            assertThat(AccumulationSignalCalculator.fires(bars, b3x11)).isTrue();
        }

        @Test
        @DisplayName("매집일이 2일이면 발화하지 않는다")
        void doesNotFireWithFewerDays() {
            List<Candle> bars = rising(121);
            setVolume(bars, 5, 2000);
            setVolume(bars, 0, 2000);

            assertThat(AccumulationSignalCalculator.fires(bars, b3x11)).isFalse();
        }

        @Test
        @DisplayName("10일 창 밖(t−10)의 매집일은 세지 않는다")
        void dayOutsideWindowIsNotCounted() {
            List<Candle> bars = rising(121);
            setVolume(bars, 10, 2000);
            setVolume(bars, 5, 2000);
            setVolume(bars, 0, 2000);

            assertThat(AccumulationSignalCalculator.fires(bars, b3x11)).isFalse();
        }

        @Test
        @DisplayName("등락률 2.5%인 날은 거래량이 터져도 매집일이 아니다")
        void bigMoveDayIsNotAccumulationDay() {
            List<Candle> bars = rising(121);
            setVolume(bars, 9, 2000);
            setClose(bars, 5, closeAt(bars, 6) * 1.025);
            setVolume(bars, 5, 2000);
            setVolume(bars, 0, 2000);

            assertThat(AccumulationSignalCalculator.fires(bars, b3x11)).isFalse();
        }

        @Test
        @DisplayName("고저폭이 한도를 넘으면 발화하지 않고, 한도를 넓히면 같은 봉에서 발화한다")
        void rangeLimitIsApplied() {
            List<Candle> bars = rising(121);
            setVolume(bars, 9, 2000);
            setVolume(bars, 5, 2000);
            setVolume(bars, 0, 2000);
            int i = bars.size() - 1 - 12;
            Candle c = bars.get(i);
            bars.set(i, new Candle(c.date(), c.open(), c.close() * 1.15, c.low(), c.close(), c.volume()));

            assertThat(AccumulationSignalCalculator.fires(bars, b3x11)).isFalse();
            assertThat(AccumulationSignalCalculator.fires(bars, AccumulationRule.clusterBase(3, 0.17))).isTrue();
        }

        @Test
        @DisplayName("고저폭 = 20봉 최고 고가 ÷ 최저 저가 − 1")
        void baseRangeFormula() {
            List<Candle> bars = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                bars.add(new Candle(START.plusDays(i), 105, 106, 104, 105, 1000));
            }
            bars.set(24 - 19, new Candle(START.plusDays(5), 100, 101, 100, 100.5, 1000)); // 창 첫 봉 저가 100
            bars.set(24 - 3, new Candle(START.plusDays(21), 108, 110, 107, 109, 1000));  // 고가 110
            bars.set(24 - 20, new Candle(START.plusDays(4), 50, 200, 40, 60, 1000));     // 창 밖 — 무시

            assertThat(AccumulationSignalCalculator.baseRange(bars, 24)).isCloseTo(0.10, within(1e-12));
        }
    }

    // ── C. OBV 다이버전스 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("C. OBV 다이버전스 (참고용)")
    class ObvDivergence {

        private final AccumulationRule c = AccumulationRule.obvDivergence();

        /** 앞 100봉은 완만한 상승, 마지막 21봉(t−20..t)은 오름 up·내림 down을 번갈아 — 거래량은 지정 */
        private List<Candle> zigzag(double up, double down, long upVolume, long downVolume) {
            List<Candle> bars = rising(100);
            double close = 105;
            bars.add(bar(100, close, 1000));
            for (int k = 1; k <= 20; k++) {
                boolean upDay = k % 2 == 1;
                close += upDay ? up : -down;
                bars.add(bar(100 + k, close, upDay ? upVolume : downVolume));
            }
            return bars;
        }

        @Test
        @DisplayName("오른 날 거래량이 크고(OBV 상승) 20일 가격 변화가 0~3%면 발화한다")
        void firesWhenObvRisesWhilePriceFlat() {
            List<Candle> bars = zigzag(0.4, 0.3, 3000, 1000); // 20일 +1.0 (≈ +0.95%)

            assertThat(AccumulationSignalCalculator.fires(bars, c)).isTrue();
        }

        @Test
        @DisplayName("내린 날 거래량이 크면(OBV 하락) 발화하지 않는다")
        void doesNotFireWhenObvFalls() {
            List<Candle> bars = zigzag(0.4, 0.3, 1000, 3000);

            assertThat(AccumulationSignalCalculator.fires(bars, c)).isFalse();
        }

        @Test
        @DisplayName("20일 가격 변화가 3%를 넘거나 음수면 발화하지 않는다")
        void priceChangeMustStayInBand() {
            List<Candle> tooHigh = zigzag(0.7, 0.3, 3000, 1000); // +4.0 (≈ +3.8%)
            List<Candle> negative = zigzag(0.3, 0.4, 3000, 1000); // −1.0

            assertThat(AccumulationSignalCalculator.fires(tooHigh, c)).isFalse();
            assertThat(AccumulationSignalCalculator.fires(negative, c)).isFalse();
        }
    }

    // ── 규칙의 길이 계산 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("관문 길이는 기본 31봉, 추세 조건이 있으면 최소 121봉 — 평균 창 16·24일이면 27·35봉")
    void ruleLengths() {
        assertThat(A3.guardBars()).isEqualTo(31);
        assertThat(A3.requiredBars()).isEqualTo(121);
        assertThat(A3_NT.requiredBars()).isEqualTo(31);
        assertThat(A3.withVolumeAvgWindow(24).guardBars()).isEqualTo(35);
        assertThat(A3.withVolumeAvgWindow(16).guardBars()).isEqualTo(27);
    }
}
