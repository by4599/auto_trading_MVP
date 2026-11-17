package com.trading.backtest;

/**
 * 그 런에 <b>실제로 엔진에 걸린</b> 거래비용 (cost-lab 전용, 다른 랩은 null).
 *
 * <p>프로필 라벨("C2 0.60%")은 사람이 적은 <b>의도값</b>일 뿐이다. 역산 수식이 틀리면
 * 라벨은 그대로인 채 엔진에는 다른 비용이 걸리고, 리포트는 아무 말도 하지 않는다(F-C1).
 * 그래서 이 값은 반드시 세팅 <b>직후 홀더에서 다시 읽어</b> 채운다 — 목표값을 그대로
 * 옮겨 적으면 검증 능력이 0이 된다.
 */
public record AppliedCost(double slippageRate, double roundTripCost) {}
