package com.trading.position;

import java.util.List;
import java.util.OptionalDouble;

/**
 * 잔고 총자산 대조 결과 — 증권사가 준 총자산을 우리가 직접 다시 계산한 값과 비교한다 (결함 6, 2026-10-11).
 *
 * <p>왜: KIS 잔고 응답의 총자산(tot_evlu_amt)이 가끔 튄다. 전고점은 리셋이 없어 한 번 튄 값이 들어가면 굳고,
 * 2026-09-21에는 그 오염(10,890,158원)으로 7거래일간 매 개장 직후 강제청산이 났다. paper 낙폭 한도가 8%라
 * 이제 전고점이 +5.11%만 튀어도 멈추는데, 기존 상한(실측 최대 × 1.15)은 그 구간을 통과시킨다.
 *
 * <p>식: <b>계산값 = D+2 정산 현금(prvs_rcdl_excc_amt) + Σ(보유수량 × 현재가)</b>. KIS 정의상
 * 총평가금액 = 가수도정산금액(D+2) + 유가평가금액이므로 현금은 반드시 D+2여야 한다. D+0 예수금(dnca_tot_amt)을
 * 쓰면 매수·매도 뒤 결제일까지 이틀간 정상 응답도 어긋난다.
 *
 * <p>판정은 전고점 인정 여부({@code ShadowPortfolio})에만 쓴다 — 청산·손절·매수 차단 판정은 이 값을 보지 않는다
 * (식이 1주일 기록으로 검증되기 전이므로, 그 연결은 후속 과제).
 *
 * @param verdict       판정
 * @param brokerTotal   증권사 총자산(tot_evlu_amt) — 판정 불가면 0
 * @param settledCash   D+2 정산 현금(prvs_rcdl_excc_amt) — 판정 불가면 0
 * @param holdingsValue Σ(보유수량 × 현재가) — 판정 불가면 0
 */
public record EquityCrossCheck(Verdict verdict, double brokerTotal, double settledCash, double holdingsValue) {

    public enum Verdict {
        /** 허용오차 안 — 전고점으로 인정 가능 */
        MATCH,
        /** 허용오차 밖 — 전고점으로 인정하지 않는다 */
        MISMATCH,
        /** 잴 재료가 없음(칸 없음·총자산 0 이하) — 막지 않는다(예전과 같은 동작) */
        UNCHECKED
    }

    /**
     * 허용오차 1% — |증권사 − 계산값| / 증권사가 이보다 크면 불일치.
     *
     * <p>근거: 막아야 할 가장 작은 위험한 오염은 +5.11%(8% 한도에서 강제정지를 부르는 전고점 상승폭)이고,
     * 정상이라면 식은 수수료 수준(~0.1%) 안에서 맞아야 한다. 1%는 두 값 사이에서 양쪽 모두 넉넉하다.
     * 실제 정상 차이는 하루 1회 기준선 로그(BalanceRawDiagnostics)로 1주일간 확인한다.
     */
    public static final double TOLERANCE = 0.01;

    private static final EquityCrossCheck NOT_CHECKED = new EquityCrossCheck(Verdict.UNCHECKED, 0, 0, 0);

    /** 판정 불가 — 대조할 재료가 없는 모든 경로(기존 3인자 스냅샷·백테스트 포함)의 기본값 */
    public static EquityCrossCheck unchecked() {
        return NOT_CHECKED;
    }

    /**
     * @param brokerTotal 증권사 총자산(tot_evlu_amt)
     * @param settledCash D+2 정산 현금 — 칸이 없거나 숫자가 아니면 empty
     * @param holdings    보유 종목(수량 0 행은 이미 빠진 목록)
     */
    public static EquityCrossCheck evaluate(double brokerTotal, OptionalDouble settledCash,
                                            List<BalanceClient.Holding> holdings) {
        if (!Double.isFinite(brokerTotal) || brokerTotal <= 0) return NOT_CHECKED;
        if (settledCash.isEmpty() || !Double.isFinite(settledCash.getAsDouble())) return NOT_CHECKED;

        double cash = settledCash.getAsDouble();
        double held = holdingsValue(holdings);
        double ratio = Math.abs(brokerTotal - (cash + held)) / brokerTotal;
        // NaN(보유 현재가가 망가진 경우)은 "허용오차 안"이 아니다 — 잴 수 없는 값을 일치로 인정하지 않는다
        Verdict verdict = ratio <= TOLERANCE ? Verdict.MATCH : Verdict.MISMATCH;
        return new EquityCrossCheck(verdict, brokerTotal, cash, held);
    }

    /** Σ(보유수량 × 현재가) */
    public static double holdingsValue(List<BalanceClient.Holding> holdings) {
        return holdings.stream().mapToDouble(h -> (double) h.quantity() * h.currentPrice()).sum();
    }

    /** 계산값 = D+2 정산 현금 + Σ(보유수량 × 현재가) */
    public double computedTotal() {
        return settledCash + holdingsValue;
    }

    /** (증권사 − 계산값) / 증권사 — 부호는 증권사 쪽이 큰지(+) 작은지(−). 판정 불가면 0 */
    public double diffRatio() {
        if (verdict == Verdict.UNCHECKED) return 0;
        return (brokerTotal - computedTotal()) / brokerTotal;
    }

    public boolean isMismatch() {
        return verdict == Verdict.MISMATCH;
    }
}
