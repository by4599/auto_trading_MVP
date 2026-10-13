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
     * @param equityCheck     총자산 대조 판정(2026-10-11, 결함 6) — 같은 응답에서 나온 판정이라 값과 판정이 어긋날 틈이 없다.
     *                        지금은 전고점 인정 여부에만 쓰인다. null이면 판정 불가로 받는다
     */
    record BalanceSnapshot(double totalAssetValue, double deposit, List<Holding> holdings,
                           EquityCrossCheck equityCheck) {

        public BalanceSnapshot {
            if (equityCheck == null) equityCheck = EquityCrossCheck.unchecked();
        }

        /** 대조 재료가 없는 구현체(백테스트·테스트 픽스처 등)용 — 판정 불가 = 예전과 같은 동작 */
        public BalanceSnapshot(double totalAssetValue, double deposit, List<Holding> holdings) {
            this(totalAssetValue, deposit, holdings, EquityCrossCheck.unchecked());
        }
    }

    record Holding(String stockCode, int quantity, double averagePrice, double currentPrice) {}
}
