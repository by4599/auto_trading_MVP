package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 후보 유니버스(§14.1 고정 54종목)를 쓰는 랩 모드 라우팅.
 *
 * <p>여기 모드들은 전역 유니버스·부동 기간이 아니라 <b>고정 후보 설정</b>으로 돌아 재현성이 있다.
 * 창은 목적에 따라 셋 중 하나다: candidate(§14.1 재현) / stress(약세장 6.5년) /
 * crash-vol(급락 측정 전용). 모드 문자열은 서로 배타적이라 검사 순서는 결과에 영향이 없다 —
 * 읽기 좋게 주제별로 묶었다.
 */
@Component
@Profile("backtest")
public class CandidateLabRouter {

    private static final Logger log = LoggerFactory.getLogger(CandidateLabRouter.class);

    private final BacktestDataProperties properties;
    private final CandleBackfillService backfillService;
    private final StrategyToggles toggles;
    private final RiskLab riskLab;
    private final CostLab costLab;
    private final CrashVolLab crashVolLab;
    private final RegimeLab regimeLab;
    private final DonchianLab donchianLab;
    private final VbFilterLab vbFilterLab;

    public CandidateLabRouter(BacktestDataProperties properties,
                              CandleBackfillService backfillService,
                              StrategyToggles toggles,
                              RiskLab riskLab,
                              CostLab costLab,
                              CrashVolLab crashVolLab,
                              RegimeLab regimeLab,
                              DonchianLab donchianLab,
                              VbFilterLab vbFilterLab) {
        this.properties = properties;
        this.backfillService = backfillService;
        this.toggles = toggles;
        this.riskLab = riskLab;
        this.costLab = costLab;
        this.crashVolLab = crashVolLab;
        this.regimeLab = regimeLab;
        this.donchianLab = donchianLab;
        this.vbFilterLab = vbFilterLab;
    }

    /** 이 라우터가 처리한 모드면 true */
    public boolean route(String mode) {
        return routeSizingAndCostLabs(mode)
                || routeRegimeLabs(mode)
                || routeDonchianLabs(mode)
                || routeVbLabs(mode);
    }

    // ── 사이징·비용·급락 측정 (창: candidate / crash-vol) ────────────────────────

    private boolean routeSizingAndCostLabs(String mode) {
        // Risk Lab (§14) — MA 진입 + P3 다일 트레일링 고정, 사이징·동시보유 스윕.
        // §14.1 후보 검증은 전역 symbols/from/to(부동)가 아니라 고정 후보 설정으로 재현한다.
        if ("risk-lab".equalsIgnoreCase(mode)) {
            LabScope scope = candidateScope("risk-lab", "MA 정배열 + P3 다일 트레일링", "MA-P3");
            riskLab.run(scope, toggles::enableMaBreakoutOnly, properties.isWriteBaseline());
            return true;
        }
        // Donchian Risk Lab (2026-08) — Donchian P3가 exit-lab에서 MDD 15.6%로 0.6%p만 초과했다.
        // 진입=Donchian, 출구=P3 고정, 사이징만 스윕해 MDD를 §4(≤15%) 안으로 넣는지 본다.
        // MA와 동일한 54-후보/후보창을 써서 apples-to-apples 비교. 기준선은 안 건드린다.
        if ("donchian-risk-lab".equalsIgnoreCase(mode)) {
            LabScope scope = candidateScope("donchian-risk-lab",
                    "돈치안 돌파 + P3 다일 트레일링", "DONCHIAN-P3");
            riskLab.run(scope, toggles::enableDonchianOnly, false);
            return true;
        }
        // RSI(2) 평균회귀(전략3=스캘핑 대체 후보, 2026-08) — 과매도 종가 진입 + P3 고정,
        // 사이징만 스윕. MA·Donchian과 동일한 54-후보/후보창. 기준선 미기록.
        if ("rsi-risk-lab".equalsIgnoreCase(mode)) {
            LabScope scope = candidateScope("rsi-risk-lab",
                    "RSI(2) 평균회귀 + P3 다일 트레일링", "RSI2-P3");
            riskLab.run(scope, toggles::enableRsiOnly, false);
            return true;
        }
        // Cost Lab (§14.1) — 검증 후보(MA+P3+RR1) 고정, 왕복 거래비용만 상향 스윕.
        if ("cost-lab".equalsIgnoreCase(mode)) {
            List<String> symbols = prepareCandidateUniverse(
                    "cost-lab", properties.getCandidateFrom(), properties.getCandidateTo());
            costLab.run(symbols, properties.getCandidateFrom(), properties.getCandidateTo());
            return true;
        }
        // Crash-Vol (BACKLOG 2026-07-24 탐색) — 지수 급락 직후 저변동성 종목 전방수익률 측정.
        // 유니버스는 후보 54종목 그대로 쓰되 기간은 전용 crash-vol-from/to(2020-01~)를 쓴다 —
        // §14.1 판정 창(2023-07~)에는 진짜 하락장이 없어 코로나·금리쇼크를 못 본다.
        if ("crash-vol".equalsIgnoreCase(mode)) {
            List<String> symbols = prepareCandidateUniverse(
                    "crash-vol", properties.getCrashVolFrom(), properties.getCrashVolTo());
            crashVolLab.run(symbols, properties.getCrashVolFrom(), properties.getCrashVolTo());
            return true;
        }
        return false;
    }

    // ── 지수 추세 필터 A/B (창: stress — 2020 코로나·2022 금리쇼크 포함) ──────────

    private boolean routeRegimeLabs(String mode) {
        // Regime Lab (§14.4) — 지수 하락 추세 진입금지 필터 A/B (측정, 채택 아님).
        // §14.3에서 후보를 무너뜨린 2022 휩쏘가 이 필터로 제거되는지 본다.
        if ("regime-lab".equalsIgnoreCase(mode)) {
            LabScope scope = stressScope("regime-lab",
                    RegimeLab.REGIME_LAB_LABEL, RegimeLab.REGIME_LAB_SLUG);
            regimeLab.run(scope, toggles::enableMaBreakoutOnlyResetAll, true);
            return true;
        }
        // 신규 후보 약세장 검증 (2026-08) — Donchian/RSI 진입 + P3 + RR1 고정, 지수 추세 필터 A/B를
        // MA와 동일한 6.5년 stress 창에서 태운다. §14.1 후보창(상승장) 합격이 약세장에서도
        // 유지되는지가 실전 후보 자격의 실질 관문이다.
        if ("donchian-regime-lab".equalsIgnoreCase(mode)) {
            LabScope scope = stressScope("donchian-regime-lab",
                    "돈치안 돌파 + P3 다일 트레일링 + RR1(0.5R·동시5)", "DONCHIAN-P3-RR1");
            regimeLab.run(scope, toggles::enableDonchianOnlyResetRsi, false);
            return true;
        }
        if ("rsi-regime-lab".equalsIgnoreCase(mode)) {
            LabScope scope = stressScope("rsi-regime-lab",
                    "RSI(2) 평균회귀 + P3 다일 트레일링 + RR1(0.5R·동시5)", "RSI2-P3-RR1");
            regimeLab.run(scope, toggles::enableRsiOnly, false);
            return true;
        }
        // Regime Sens (§14.4 민감도) — regime-lab의 승자 MA120 ±20%(MA96·MA144) 단일 파라미터
        // 민감도. 고정 조건·창은 regime-lab과 동일, MA 기간만 스윕한다.
        if ("regime-sens".equalsIgnoreCase(mode)) {
            List<String> symbols = prepareCandidateUniverse(
                    "regime-sens", properties.getStressFrom(), properties.getStressTo());
            regimeLab.runSensitivity(symbols, properties.getStressFrom(), properties.getStressTo());
            return true;
        }
        return false;
    }

    // ── 돈치안 후보 강건성 (창: stress, 지수 MA120 ON) ───────────────────────────

    private boolean routeDonchianLabs(String mode) {
        // Step 2·3 — 약세장 관문을 통과한 돈치안(+지수 MA120)을 고정하고 ①자체 파라미터 ±20%
        // ②왕복 비용 상향에서도 성과가 완만한지 본다.
        if ("donchian-sens".equalsIgnoreCase(mode)) {
            runDonchianSensitivity("donchian-sens", DonchianLab.SIZING_RR1);
            return true;
        }
        if ("donchian-cost-lab".equalsIgnoreCase(mode)) {
            runDonchianCostLab("donchian-cost-lab", DonchianLab.SIZING_RR1);
            return true;
        }
        // §15.6 결론 — 0.5R의 MDD 잣대가 노이즈였으므로 SZ2(0.25R·동시5)로 고정해 두 민감도를
        // 재시험한다. D0(추세120)는 §15.6 SZ2(1181건·PF 1.92·MDD 4.2%)를, C0(0.41%)는 같은 값을
        // 재현해야 하는 앵커.
        if ("donchian-sens-sz2".equalsIgnoreCase(mode)) {
            runDonchianSensitivity("donchian-sens-sz2", DonchianLab.SIZING_SZ2);
            return true;
        }
        if ("donchian-cost-sz2".equalsIgnoreCase(mode)) {
            runDonchianCostLab("donchian-cost-sz2", DonchianLab.SIZING_SZ2);
            return true;
        }
        // §15.5 후속 ①: MDD가 유일 병목이므로 약세장 창에서 사이징을 직접 재스윕 (SZ0=G1 재현 앵커)
        if ("donchian-stress-sizing".equalsIgnoreCase(mode)) {
            List<String> symbols = prepareCandidateUniverse(
                    "donchian-stress-sizing", properties.getStressFrom(), properties.getStressTo());
            donchianLab.runStressSizing(symbols, properties.getStressFrom(), properties.getStressTo());
            return true;
        }
        // §15.5 후속 ②: 종목 추세 기간 96~192 지도 — 144가 평지의 일부인지 외딴 봉우리인지
        if ("donchian-trend-map".equalsIgnoreCase(mode)) {
            List<String> symbols = prepareCandidateUniverse(
                    "donchian-trend-map", properties.getStressFrom(), properties.getStressTo());
            donchianLab.runTrendMap(symbols, properties.getStressFrom(), properties.getStressTo());
            return true;
        }
        return false;
    }

    // ── B동(당일 단타) 진입 조건·비용 (창: stress) ──────────────────────────────

    private boolean routeVbLabs(String mode) {
        // B동 진입 조건 A/B (2026-08-04, ADR-001 개정 결정 2) — VB 진입 + 현행 paper 출구·사이징을
        // 그대로 고정하고 진입 필터만 스윕한다. 이긴 조합이 없으면 B동 존치 자체를 재검토한다.
        if ("vb-filter-lab".equalsIgnoreCase(mode)) {
            List<String> symbols = prepareCandidateUniverse(
                    "vb-filter-lab", properties.getStressFrom(), properties.getStressTo());
            vbFilterLab.runFilterLab(symbols, properties.getStressFrom(), properties.getStressTo());
            return true;
        }
        // B동 비용 하향 스윕 (2026-08-05) — "왜 유명한 기법이 마이너스인가"의 답을 찾는다.
        // 기대값은 1차적으로 (총이익률 − 왕복비용)이므로 비용을 낮춰가며 부호가 뒤집히는
        // 지점을 본다. 슬리피지 0에서도 수수료 0.03% + 매도 제세 0.18% = 0.21%가 법정 바닥이다.
        if ("vb-cost-lab".equalsIgnoreCase(mode)) {
            List<String> symbols = prepareCandidateUniverse(
                    "vb-cost-lab", properties.getStressFrom(), properties.getStressTo());
            vbFilterLab.runCostLab(symbols, properties.getStressFrom(), properties.getStressTo());
            return true;
        }
        return false;
    }

    private void runDonchianSensitivity(String mode, DonchianLab.DonchianSizing sizing) {
        List<String> symbols = prepareCandidateUniverse(
                mode, properties.getStressFrom(), properties.getStressTo());
        donchianLab.runSensitivity(symbols, properties.getStressFrom(), properties.getStressTo(), sizing);
    }

    private void runDonchianCostLab(String mode, DonchianLab.DonchianSizing sizing) {
        List<String> symbols = prepareCandidateUniverse(
                mode, properties.getStressFrom(), properties.getStressTo());
        donchianLab.runCostLab(symbols, properties.getStressFrom(), properties.getStressTo(), sizing);
    }

    private LabScope candidateScope(String mode, String label, String slug) {
        LocalDate from = properties.getCandidateFrom();
        LocalDate to = properties.getCandidateTo();
        return new LabScope(label, slug, prepareCandidateUniverse(mode, from, to), from, to);
    }

    private LabScope stressScope(String mode, String label, String slug) {
        LocalDate from = properties.getStressFrom();
        LocalDate to = properties.getStressTo();
        return new LabScope(label, slug, prepareCandidateUniverse(mode, from, to), from, to);
    }

    /**
     * §14.1 후보 유니버스(54종목)를 idempotent 백필하고, 이번 실행이 부동 날짜가 아닌 고정 후보
     * 설정으로 도는 재현성 실행임을 로그로 남긴다. targetSymbols는 건드리지 않으므로
     * (backfillExtra는 추가 표본만 적재) VB 유니버스는 오염되지 않는다.
     */
    private List<String> prepareCandidateUniverse(String mode, LocalDate from, LocalDate to) {
        List<String> candidateSymbols = properties.getCandidateSymbols();
        int extra = backfillService.backfillExtra(candidateSymbols);
        log.info("[Orchestrator] {} 후보 유니버스 백필: 신규 {}건 (idempotent — 기존은 스킵)", mode, extra);
        log.info("[Orchestrator] {} 후보 고정 설정 사용(candidate-symbols {}개, 기간 {}~{}) — 재현성 실행",
                mode, candidateSymbols.size(), from, to);
        return candidateSymbols;
    }
}
