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
