package com.trading.position;

/**
 * peakEquity(전고점)의 실측 근거 검증기.
 *
 * peakEquity는 ADR 2.2에 따라 "단조 증가 + 리셋 없음"이라 한 번 오염되면 스스로 복구되지
 * 않는다. 2026-08-12 실제 사고: portfolio_state.PEAK_EQUITY = 13,027,929원이 저장돼 있었으나
 * daily_equity 24거래일 전체 이력의 MAX(start_equity)는 10,000,000원(시드 자본)으로,
 * 계좌가 그 금액에 도달한 적이 없었다. 이 가짜 전고점 때문에 GlobalEquityStopRule(매수 게이트)과
 * RiskMonitor(청산 트리거)가 MDD -23%로 오판해 신규 매수가 영구 거부됐다.
 *
 * 프로필별 구현체 교체(코딩 컨벤션): 모의투자는 실측 근거 검증
 * ({@link EvidenceBasedPeakEquityCalibrator}), 백테스트는 무검증
 * ({@link NoOpPeakEquityCalibrator}) — 백테스트 결정성(G0 회귀 앵커)을 건드리지 않기 위함.
 */
public interface PeakEquityCalibrator {

    /**
     * 저장된 전고점이 실측 근거상 불가능하면 가능한 상한으로 낮춰 반환한다.
     * 근거가 없거나 조회에 실패하면 원값을 그대로 둔다 — 근거 없이 안전장치의
     * 기준선을 임의로 건드리지 않는다.
     */
    double calibrate(double storedPeak);

    /**
     * 이번 스냅샷의 총자산이 실측 근거상 불가능하게 큰지 여부.
     * true면 전고점을 갱신하지 않는다 — 잘못된 잔고 한 번이 전고점을 영구 오염시키는 경로를 끊는다.
     */
    boolean isImplausible(double equity);

    /**
     * 전고점이 <b>실제로 올라갔을 때만</b> 1회 호출된다 (같은 값 재확인·하락에는 호출되지 않는다).
     *
     * <p>알림을 여기에 둔 이유: 전고점 관련 동작 중 프로필마다 달라야 하는 것은 이미 이 인터페이스가
     * 가르는 자리다. {@code ShadowPortfolio}에 알림 도구를 직접 주입하면 백테스트
     * ({@code BacktestRunner}가 봉마다 tick을 돌린다)에서 시뮬레이션이 텔레그램을 두드리는 경로가
     * 생긴다 — yml의 빈 토큰은 OS 환경변수에 지므로 구조로 막아야 한다(BACKTEST-DESIGN §12).
     * 기본 구현은 무동작이라 {@link NoOpPeakEquityCalibrator}는 아무것도 보내지 않는다.
     *
     * @param previous 올라가기 직전의 전고점 (0이면 최초 확립 — '경신'이 아니다)
     * @param current  새 전고점
     */
    default void notifyPeakRaised(double previous, double current) {}
}
