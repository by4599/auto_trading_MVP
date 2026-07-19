package com.trading.risk;

import com.trading.bucket.BucketAccountService;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.StrategyBucket;
import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

/**
 * 지갑 칸 예산 지킴이 (실험 설계 2026-07-19).
 *
 * 매수 신호의 칸이 잠겨 있거나(방식2·3은 재료 확보 전까지 비활성) 가용 현금이
 * 소진됐으면 진입을 차단한다. 세부 수량 축소는 OrderSizingService가 맡고,
 * 이 룰은 "그 칸으로 살 수 있는 상태인가"만 판정한다.
 *
 * trading.bucket.enabled=false(백테스트 포함 기본값)면 무조건 통과 —
 * 칸 실험이 꺼진 환경의 동작을 바꾸지 않는다.
 */
@Component
public class BucketBudgetRule implements RiskRule {

    private final BucketProperties properties;
    private final BucketAccountService accountService;

    public BucketBudgetRule(BucketProperties properties, BucketAccountService accountService) {
        this.properties = properties;
        this.accountService = accountService;
    }

    @Override
    public RiskResult validate(Signal signal, Account account) {
        if (!properties.isEnabled() || !signal.isBuy()) return RiskResult.pass();

        StrategyBucket bucket = StrategyBucket.orDefault(signal.getBucket());
        if (!properties.isBucketActive(bucket)) {
            return RiskResult.reject(String.format(
                    "칸 비활성: %s — 재료(검증 통과 신호) 확보 전까지 매수 잠금", bucket));
        }

        double cash = accountService.availableCash(bucket);
        if (cash <= 0) {
            return RiskResult.reject(String.format(
                    "칸 예산 소진: %s (가용 현금 %.0f원)", bucket, cash));
        }
        return RiskResult.pass();
    }
}
