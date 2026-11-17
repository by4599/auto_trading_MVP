package com.trading.risk;

import com.trading.bucket.BucketParameterResolver;
import com.trading.market.MarketCalendarService;
import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;

/**
 * 15:15 타임컷 이후 신규 매수 금지 — "당일 청산" 전제를 코드로 강제한다.
 *
 * <p>실측 근거: 2026-08-31 15:19:14에 005930 4주가 신규 매수돼 그대로 밤을 넘겼고
 * 다음 날 09:59에 익절 매도됐다(logs/paper-2026-08-31.0.log.gz). 그 시각엔
 * {@link com.trading.scheduler.TimeCutScheduler}가 이미 보유분을 정리한 뒤라
 * 당일 안에 회수할 출구가 없었다.
 *
 * <p>{@link MarketCloseRule}(마감 10분 전 = 평시 15:20)과 <b>판단 근거가 다르므로</b>
 * 그 룰의 컷을 당기지 않고 별도 룰로 둔다 — 그쪽은 "마감이 임박했다", 이쪽은
 * "오늘의 출구가 이미 닫혔다"이다. 두 룰은 각자 독립적으로 매수를 막는다.
 *
 * <p>시각을 마감 시각에서 유도하면 안 된다: market-calendar.yml의 2026-11-19(수능일)은
 * 마감이 16:30이라 "마감 - N분"으로 유도하면 그날만 매수 문이 16:15까지 열려 한 시간짜리
 * 구멍이 생긴다. 타임컷 cron이 15:15 고정이므로 이 룰도 같은 고정 상수여야 짝이 맞는다
 * (어긋남은 PostTimeCutBuyRuleTest의 크론 대조 테스트가 잡는다).
 *
 * <p>다일 보유 칸(multiDayHold)은 면제 — 타임컷이 그 칸을 제외하고 이월하므로 15:15
 * 이후 매수도 회수 불능이 아니다. 그 칸에도 {@link MarketCloseRule}의 마감 컷은 그대로 걸린다.
 */
@Component
public class PostTimeCutBuyRule implements RiskRule {

    /** TimeCutScheduler의 cron(0 15 15 * * MON-FRI)과 짝 — 한쪽만 바꾸면 다시 구멍이 생긴다. */
    public static final LocalTime TIME_CUT_AT = LocalTime.of(15, 15);

    private final Clock clock;
    private final MarketCalendarService marketCalendarService;
    private final BucketParameterResolver bucketParams;

    public PostTimeCutBuyRule(Clock clock,
                              MarketCalendarService marketCalendarService,
                              BucketParameterResolver bucketParams) {
        this.clock = clock;
        this.marketCalendarService = marketCalendarService;
        this.bucketParams = bucketParams;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!signal.isBuy()) return RiskResult.pass();
        if (bucketParams.multiDayHold(signal.getBucket())) return RiskResult.pass();
        // 휴장일엔 타임컷 자체가 돌지 않으므로(TimeCutScheduler 휴장일 가드) 막을 근거가 없다
        if (marketCalendarService.isHolidayToday()) return RiskResult.pass();

        LocalTime now = LocalTime.now(clock);
        if (!now.isBefore(TIME_CUT_AT)) {  // 정각 포함 — 15:15:00은 타임컷이 이미 발동한 시점
            return RiskResult.reject(String.format(
                    "%s 타임컷 이후 신규 매수 금지 (당일 청산 전제) — 현재 %s", TIME_CUT_AT, now));
        }
        return RiskResult.pass();
    }
}
