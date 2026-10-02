package com.trading.position;

import java.util.List;

/**
 * 계좌 잔고 조회 인터페이스.
 * 모의투자(KisBalanceClient) / 실전 / 백테스트 구현체를 갈아끼울 수 있다.
 */
public interface BalanceClient {

    /** 계좌 잔고 스냅샷. 실패 시 예외를 던진다 — 폴백 판단은 호출 측 책임. */
    BalanceSnapshot fetchBalance();

    /**
     * 계좌 잔고 스냅샷.
     *
     * @param totalAssetValue 예수금 + 보유종목 평가금액 (현금 포함 총자산)
     * @param deposit         예수금(현금). 수수료·세금이 이미 빠진 "실제로 손에 쥔 돈"
     * @param holdings        보유 종목
     */
    record BalanceSnapshot(double totalAssetValue, double deposit, List<Holding> holdings) {}

    record Holding(String stockCode, int quantity, double averagePrice, double currentPrice) {}
}
