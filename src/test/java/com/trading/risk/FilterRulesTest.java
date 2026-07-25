package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import com.trading.strategy.FilterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 필터 4종 스위치 — 기본 OFF일 때 무영향(회귀), ON일 때 규칙 동작 */
@DisplayName("필터 룰 — 기본 OFF 무영향 + ON 동작")
class FilterRulesTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static Account account() {
        return new Account(10_000_000, 0.0, 0, List.of());
    }

    private static Clock fixedAt(LocalTime time) {
        return Clock.fixed(LocalDateTime.of(2026, 7, 10, time.getHour(), time.getMinute())
                .atZone(KST).toInstant(), KST);
    }

    @Nested
    @DisplayName("EntryTimeWindowRule")
    class EntryWindow {

        @Test
        @DisplayName("OFF(기본)이면 09:05에도 통과한다")
        void off_passesEarly() {
            EntryTimeWindowRule rule = new EntryTimeWindowRule(
                    new FilterProperties(), fixedAt(LocalTime.of(9, 5)));
            assertThat(rule.validate(Signal.buy("005930", "t"), account()).isPass()).isTrue();
        }

        @Test
        @DisplayName("ON이면 notBefore(09:15) 이전 매수를 거부한다")
        void on_rejectsBeforeWindow() {
            FilterProperties filters = new FilterProperties();
            filters.getEntryWindow().setEnabled(true);
            EntryTimeWindowRule rule = new EntryTimeWindowRule(filters, fixedAt(LocalTime.of(9, 5)));

            assertThat(rule.validate(Signal.buy("005930", "t"), account()).isPass()).isFalse();
            // 09:15 이후는 통과
            EntryTimeWindowRule after = new EntryTimeWindowRule(filters, fixedAt(LocalTime.of(9, 20)));
            assertThat(after.validate(Signal.buy("005930", "t"), account()).isPass()).isTrue();
        }
    }

    @Nested
    @DisplayName("IndexRegimeRule")
    class IndexRegime {

        @Test
        @DisplayName("OFF(기본)이면 약세 레짐이어도 통과한다")
        void off_passes() {
            IndexRegimeRule rule = new IndexRegimeRule(
                    new FilterProperties(), () -> Optional.of(true));
            assertThat(rule.validate(Signal.buy("005930", "t"), account()).isPass()).isTrue();
        }

        @Test
        @DisplayName("ON + 갭다운이면 매수를 거부하고, 데이터 없으면 통과한다 (오탐 방지)")
        void on_rejectsBearish_passesUnknown() {
            FilterProperties filters = new FilterProperties();
            filters.getIndexRegime().setEnabled(true);

            IndexRegimeRule bearish = new IndexRegimeRule(filters, () -> Optional.of(true));
            assertThat(bearish.validate(Signal.buy("005930", "t"), account()).isPass()).isFalse();

            IndexRegimeRule bullish = new IndexRegimeRule(filters, () -> Optional.of(false));
            assertThat(bullish.validate(Signal.buy("005930", "t"), account()).isPass()).isTrue();

            IndexRegimeRule unknown = new IndexRegimeRule(filters, Optional::empty);
            assertThat(unknown.validate(Signal.buy("005930", "t"), account()).isPass()).isTrue();
        }
    }

    @Nested
    @DisplayName("IndexTrendRule (§14.4 지수 장기추세)")
    class IndexTrend {

        /** isBelowTrend만 응답하는 데이터원 — 갭다운(isBearishRegime)과 독립임을 보이려고 empty 고정 */
        private static IndexRegimeSource trendSource(java.util.function.IntFunction<Optional<Boolean>> f) {
            return new IndexRegimeSource() {
                @Override public Optional<Boolean> isBearishRegime() { return Optional.empty(); }
                @Override public Optional<Boolean> isBelowTrend(int maPeriod) { return f.apply(maPeriod); }
            };
        }

        @Test
        @DisplayName("기본값은 OFF · MA200이다 (켜지 않으면 기존 결과 불변)")
        void defaults_offAndMa200() {
            FilterProperties filters = new FilterProperties();
            assertThat(filters.getIndexTrend().isEnabled()).isFalse();
            assertThat(filters.getIndexTrend().getMaPeriod()).isEqualTo(200);
        }

        @Test
        @DisplayName("OFF(기본)이면 하락 추세여도 통과한다")
        void off_passes() {
            IndexTrendRule rule = new IndexTrendRule(
                    new FilterProperties(), trendSource(p -> Optional.of(true)));
            assertThat(rule.validate(Signal.buy("005930", "t"), account()).isPass()).isTrue();
        }

        @Test
        @DisplayName("ON + 추세 이탈이면 매수를 거부하고, 데이터 없으면 통과한다 (오탐 방지)")
        void on_rejectsBelowTrend_passesUnknown() {
            FilterProperties filters = new FilterProperties();
            filters.getIndexTrend().setEnabled(true);

            IndexTrendRule below = new IndexTrendRule(filters, trendSource(p -> Optional.of(true)));
            assertThat(below.validate(Signal.buy("005930", "t"), account()).isPass()).isFalse();

            IndexTrendRule above = new IndexTrendRule(filters, trendSource(p -> Optional.of(false)));
            assertThat(above.validate(Signal.buy("005930", "t"), account()).isPass()).isTrue();

            IndexTrendRule unknown = new IndexTrendRule(filters, trendSource(p -> Optional.empty()));
            assertThat(unknown.validate(Signal.buy("005930", "t"), account()).isPass()).isTrue();
        }

        @Test
        @DisplayName("설정한 MA 기간이 데이터원에 그대로 전달된다")
        void passesConfiguredMaPeriod() {
            FilterProperties filters = new FilterProperties();
            filters.getIndexTrend().setEnabled(true);
            filters.getIndexTrend().setMaPeriod(120);

            IndexTrendRule rule = new IndexTrendRule(filters, trendSource(p -> Optional.of(p == 120)));
            assertThat(rule.validate(Signal.buy("005930", "t"), account()).isPass()).isFalse();
        }

        @Test
        @DisplayName("갭다운 필터와 독립이다 — 한쪽만 켜면 다른 쪽 판정은 관여하지 않는다")
        void independentOfGapDownFilter() {
            FilterProperties filters = new FilterProperties();
            filters.getIndexTrend().setEnabled(true);   // 추세 ON, 갭다운은 기본 OFF

            // 갭다운은 '약세'라고 답하지만 추세는 '아래 아님' → 매수 통과
            IndexRegimeSource source = new IndexRegimeSource() {
                @Override public Optional<Boolean> isBearishRegime() { return Optional.of(true); }
                @Override public Optional<Boolean> isBelowTrend(int maPeriod) { return Optional.of(false); }
            };
            assertThat(new IndexTrendRule(filters, source)
                    .validate(Signal.buy("005930", "t"), account()).isPass()).isTrue();
            assertThat(new IndexRegimeRule(filters, source)
                    .validate(Signal.buy("005930", "t"), account()).isPass()).isTrue(); // 갭다운 OFF
        }

        @Test
        @DisplayName("isBelowTrend 기본 구현은 empty다 — 기존 구현체(paper NoOp)는 그대로 통과")
        void defaultImplementation_isEmpty() {
            IndexRegimeSource legacy = () -> Optional.of(true); // isBearishRegime만 구현
            assertThat(legacy.isBelowTrend(200)).isEmpty();
        }
    }

    @Nested
    @DisplayName("TrailingStopTracker")
    class Trailing {

        @Test
        @DisplayName("OFF(기본)이면 절대 청산 신호를 내지 않는다")
        void off_neverExits() {
            TrailingStopTracker tracker = new TrailingStopTracker(new FilterProperties());
            tracker.updateHigh("005930", 110);
            assertThat(tracker.exitPrice("005930", 90, 100)).isEmpty();
        }

        @Test
        @DisplayName("ON: +3% 도달 후 고점 대비 2% 하락 시 트레일 레벨을 반환한다")
        void on_exitsAfterArmAndTrail() {
            FilterProperties filters = new FilterProperties();
            filters.getTrailingStop().setEnabled(true); // arm 3%, trail 2% 기본

            TrailingStopTracker tracker = new TrailingStopTracker(filters);
            tracker.updateHigh("005930", 104);          // 진입가 100 → +4% 도달 (armed)

            // 고점 104 × 0.98 = 101.92 — 현재가 101이면 청산
            assertThat(tracker.exitPrice("005930", 101, 100)).hasValue(104 * 0.98);
            // 현재가 103이면 유지
            assertThat(tracker.exitPrice("005930", 103, 100)).isEmpty();
        }

        @Test
        @DisplayName("+3% 미도달이면 하락해도 청산하지 않는다 (ATR 손절 영역)")
        void notArmed_noExit() {
            FilterProperties filters = new FilterProperties();
            filters.getTrailingStop().setEnabled(true);

            TrailingStopTracker tracker = new TrailingStopTracker(filters);
            tracker.updateHigh("005930", 102);          // +2% — 미장착

            assertThat(tracker.exitPrice("005930", 99, 100)).isEmpty();
        }

        @Test
        @DisplayName("clear() 후에는 고점 기록이 사라진다 (재진입 오염 방지)")
        void clear_resetsHigh() {
            FilterProperties filters = new FilterProperties();
            filters.getTrailingStop().setEnabled(true);

            TrailingStopTracker tracker = new TrailingStopTracker(filters);
            tracker.updateHigh("005930", 110);
            tracker.clear("005930");

            assertThat(tracker.exitPrice("005930", 100, 100)).isEmpty();
        }
    }
}
