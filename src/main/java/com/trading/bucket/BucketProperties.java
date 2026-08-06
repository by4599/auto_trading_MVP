package com.trading.bucket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 지갑 칸 실험 설정.
 *
 * enabled=false(기본)면 칸 나누기 전체가 꺼진다 — 사이징은 기존처럼 계좌 전체
 * 기준으로 동작하고 BucketBudgetRule은 무조건 통과한다. 백테스트(B-3) 결정성을
 * 지키기 위해 backtest 프로필에서는 반드시 꺼져 있어야 한다 (paper에서만 켠다).
 *
 * 칸별 배분금(allocation)은 실험 시작 시점 기준 고정 원금이고,
 * 칸 자산 = 배분금 + 실험 시작일 이후 그 칸의 실현손익 누적이다 (BucketAccountService).
 */
@Component
public class BucketProperties {

    private final boolean enabled;
    private final LocalDate experimentStart;
    private final double vbAllocation;
    private final double eventAllocation;
    private final double mixAllocation;
    private final double trendAllocation;
    private final boolean eventEnabled;
    private final boolean mixEnabled;
    private final boolean trendEnabled;

    public BucketProperties(
            @Value("${trading.bucket.enabled:false}") boolean enabled,
            @Value("${trading.bucket.experiment-start:2026-07-20}") String experimentStart,
            @Value("${trading.bucket.vb-allocation:10000000}") double vbAllocation,
            @Value("${trading.bucket.event-allocation:10000000}") double eventAllocation,
            @Value("${trading.bucket.mix-allocation:10000000}") double mixAllocation,
            @Value("${trading.bucket.trend-allocation:10000000}") double trendAllocation,
            @Value("${trading.bucket.event-enabled:false}") boolean eventEnabled,
            @Value("${trading.bucket.mix-enabled:false}") boolean mixEnabled,
            @Value("${trading.bucket.trend-enabled:false}") boolean trendEnabled) {
        this.enabled = enabled;
        this.experimentStart = LocalDate.parse(experimentStart);
        this.vbAllocation = vbAllocation;
        this.eventAllocation = eventAllocation;
        this.mixAllocation = mixAllocation;
        this.eventEnabled = eventEnabled;
        this.mixEnabled = mixEnabled;
        this.trendAllocation = trendAllocation;
        this.trendEnabled = trendEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDate getExperimentStart() {
        return experimentStart;
    }

    public double allocationOf(StrategyBucket bucket) {
        return switch (bucket) {
            case VB    -> vbAllocation;
            case EVENT -> eventAllocation;
            case MIX   -> mixAllocation;
            case TREND -> trendAllocation;
        };
    }

    /** 방식2/3은 재료 확보 전까지, A동(TREND)은 ADR-001 개정 승인 전까지 잠금 — 방식1만 기본 활성 */
    public boolean isBucketActive(StrategyBucket bucket) {
        return switch (bucket) {
            case VB    -> true;
            case EVENT -> eventEnabled;
            case MIX   -> mixEnabled;
            case TREND -> trendEnabled;
        };
    }
}
