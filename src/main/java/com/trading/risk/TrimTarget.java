package com.trading.risk;

// ADR-002: TrimTarget 선정 알고리즘은 CapacityScalingEngine에서 담당.
// LiquidationService는 대상 선정에 관여하지 않는다.
public record TrimTarget(String ticker, int quantity) {}
