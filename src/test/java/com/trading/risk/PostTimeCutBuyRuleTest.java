package com.trading.risk;

import com.trading.backtest.DailyBarSimulator;
import com.trading.bucket.BucketParameterResolver;
import com.trading.bucket.BucketParameters;
import com.trading.bucket.StrategyBucket;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.Account;
import com.trading.scheduler.TimeCutScheduler;
import com.trading.signal.Signal;
import com.trading.strategy.FilterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 15:15 타임컷과 15:20 마감 컷 사이 5분 구멍을 막는 룰 — 2026-08-31 15:19:14에
 * 005930 4주가 이 구멍으로 들어와 오버나잇 보유됐다.
 *
 * Clock은 KST 고정(F-8과 동일 패턴), 칸 리졸버는 실객체로 조립한다
 * (Java 25 인라인 Mockito 제약 — 구체 클래스 목킹 금지).
 */
@DisplayName("PostTimeCutBuyRule — 15:15 타임컷 이후 신규 매수 금지")
class PostTimeCutBuyRuleTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate INCIDENT_DAY = LocalDate.of(2026, 8, 31); // 월요일 — 실사고 당일
    private static final Account ACCOUNT = new Account(1_000_000, 0.0, 0, List.of());

    private static PostTimeCutBuyRule rule(LocalDate date, LocalTime time,
                                           List<LocalDate> holidays, BucketParameters params) {
        Clock fixed = Clock.fixed(LocalDateTime.of(date, time).atZone(KST).toInstant(), KST);
        MarketCalendarProperties props = new MarketCalendarProperties();
        props.setHolidays(holidays);
        return new PostTimeCutBuyRule(fixed,
                new MarketCalendarService(props, fixed),
                new BucketParameterResolver(params, new RiskLimitsProperties(), new FilterProperties()));
    }

    /** 평시 거래일 · 칸 오버라이드 없음 (= 전 칸 당일 청산) */
    private static PostTimeCutBuyRule ruleAt(LocalTime time) {
        return rule(INCIDENT_DAY, time, List.of(), new BucketParameters());
    }

    private static BucketParameters multiDayHold(StrategyBucket bucket) {
        BucketParameters params = new BucketParameters();
        BucketParameters.Overrides overrides = new BucketParameters.Overrides();
        overrides.setMultiDayHold(true);
        params.getOverrides().put(bucket, overrides);
        return params;
    }

    @Test
    @DisplayName("15:14:59 매수 → 통과 (타임컷 직전은 아직 당일 청산 가능)")
    void buy_passes_just_before_time_cut() {
        assertThat(ruleAt(LocalTime.of(15, 14, 59))
                .validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("15:15:00 정각 매수 → 차단 (경계 포함 — 그 순간 타임컷이 발동한다)")
    void buy_rejected_at_exact_time_cut() {
        assertThat(ruleAt(LocalTime.of(15, 15, 0))
                .validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isFalse();
    }

    @Test
    @DisplayName("회귀 — 2026-08-31 15:19:14 005930 오버나잇 사고: 15:19 매수는 차단돼야 한다")
    void buy_rejected_in_the_five_minute_hole_that_caused_the_2026_08_31_overnight() {
        RiskResult result = ruleAt(LocalTime.of(15, 19))
                .validate(Signal.buy("005930", "VolatilityBreakout"), ACCOUNT);

        assertThat(result.isPass()).isFalse();
        assertThat(result.getReason()).contains("15:15");
    }

    @Test
    @DisplayName("15:19 매도 → 통과 (타임컷 자신의 매도를 막으면 안 된다)")
    void sell_always_passes() {
        assertThat(ruleAt(LocalTime.of(15, 19))
                .validate(Signal.sell("005930", "TimeCut-1515"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("15:19 매수라도 다일 보유 칸이면 통과 (타임컷 제외 대상이라 이월이 정상)")
    void buy_passes_for_multi_day_hold_bucket() {
        PostTimeCutBuyRule rule = rule(INCIDENT_DAY, LocalTime.of(15, 19),
                List.of(), multiDayHold(StrategyBucket.TREND));

        assertThat(rule.validate(Signal.buy("005930", "Donchian", StrategyBucket.TREND), ACCOUNT)
                .isPass()).isTrue();
    }

    @Test
    @DisplayName("휴장일 15:19 매수 → 통과 (그날은 타임컷이 돌지 않으므로 막을 근거가 없다)")
    void buy_passes_on_holiday() {
        PostTimeCutBuyRule rule = rule(INCIDENT_DAY, LocalTime.of(15, 19),
                List.of(INCIDENT_DAY), new BucketParameters());

        assertThat(rule.validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("백테스트 진입 시각은 영향받지 않는다 — 실제 상수를 대조해 드리프트까지 잡는다")
    void backtest_entry_times_are_unaffected() {
        assertThat(DailyBarSimulator.BREAKOUT_ENTRY_AT).isBefore(PostTimeCutBuyRule.TIME_CUT_AT);
        assertThat(DailyBarSimulator.CLOSE_ENTRY_AT).isBefore(PostTimeCutBuyRule.TIME_CUT_AT);

        assertThat(ruleAt(DailyBarSimulator.BREAKOUT_ENTRY_AT)
                .validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
        assertThat(ruleAt(DailyBarSimulator.CLOSE_ENTRY_AT)
                .validate(Signal.buy("005930", "t"), ACCOUNT).isPass()).isTrue();
    }

    @Test
    @DisplayName("크론 드리프트 가드 — TimeCutScheduler cron과 TIME_CUT_AT이 어긋나면 실패한다")
    void rule_time_matches_time_cut_cron() throws NoSuchMethodException {
        Scheduled scheduled = TimeCutScheduler.class.getMethod("run").getAnnotation(Scheduled.class);

        LocalDateTime nextFire = CronExpression.parse(scheduled.cron())
                .next(LocalDateTime.of(INCIDENT_DAY, LocalTime.MIDNIGHT));

        assertThat(nextFire).isNotNull();
        assertThat(nextFire.toLocalTime()).isEqualTo(PostTimeCutBuyRule.TIME_CUT_AT);
    }
}
