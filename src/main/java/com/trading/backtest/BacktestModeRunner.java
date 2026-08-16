package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 백테스트 모드 선택·실행 — 백필 → 유니버스 확정 → {@code --backtest.mode} 분기.
 *
 * <p>분기 순서: 데이터 이관/이벤트/스모크 → 단일 전략 재생·Exit Lab →
 * 후보 유니버스 랩({@link CandidateLabRouter}) → 어느 것도 아니면 full 모드(VB 전체 검증).
 */
@Component
@Profile("backtest")
public class BacktestModeRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestModeRunner.class);

    private final CandleBackfillService backfillService;
    private final BacktestDataProperties properties;
    private final LiquidityScreener liquidityScreener;
    private final Clock clock;
    private final MinuteCandleImporter minuteCandleImporter;
    private final EventBacktestPipeline eventPipeline;
    private final BacktestRunner runner;
    private final StrategyToggles toggles;
    private final SingleStrategyLab singleStrategyLab;
    private final ExitLab exitLab;
    private final FullBacktestLab fullBacktestLab;
    private final CandidateLabRouter candidateLabRouter;

    public BacktestModeRunner(CandleBackfillService backfillService,
                              BacktestDataProperties properties,
                              LiquidityScreener liquidityScreener,
                              Clock clock,
                              MinuteCandleImporter minuteCandleImporter,
                              EventBacktestPipeline eventPipeline,
                              BacktestRunner runner,
                              StrategyToggles toggles,
                              SingleStrategyLab singleStrategyLab,
                              ExitLab exitLab,
                              FullBacktestLab fullBacktestLab,
                              CandidateLabRouter candidateLabRouter) {
        this.backfillService = backfillService;
        this.properties = properties;
        this.liquidityScreener = liquidityScreener;
        this.clock = clock;
        this.minuteCandleImporter = minuteCandleImporter;
        this.eventPipeline = eventPipeline;
        this.runner = runner;
        this.toggles = toggles;
        this.singleStrategyLab = singleStrategyLab;
        this.exitLab = exitLab;
        this.fullBacktestLab = fullBacktestLab;
        this.candidateLabRouter = candidateLabRouter;
    }

    public void execute() {
        log.info("[Orchestrator] candle_history 백필 검사 시작");
        int saved = backfillService.backfillAll();
        log.info("[Orchestrator] 백필 완료: 신규 {}건", saved);

        LocalDate from = LocalDate.now(clock).minusYears(properties.getYears());
        LocalDate to = backfillService.rangeTo();
        // 유동성 필터 (B-3 유니버스 확장 재검증) — 중소형 후보 중 거래대금 미달 제외.
        // 이벤트 모드(events)는 별도 표본 로직(EventBacktestPipeline)을 쓰므로 영향 없다.
        List<String> symbols = liquidityScreener.filter(
                backfillService.targetSymbols(), to, properties.getMinDailyTradingValue());

        String mode = properties.getMode();
        if (runDataMode(mode, symbols, from, to)) return;
        if (runStrategyMode(mode, symbols, from, to)) return;
        if (candidateLabRouter.route(mode)) return;

        fullBacktestLab.run(symbols, from, to);
    }

    /** 데이터 이관·이벤트 통계·스모크 — 전략 재생과 무관한 단독 모드 */
    private boolean runDataMode(String mode, List<String> symbols, LocalDate from, LocalDate to) {
        // 분봉 이관 (2026-08-04) — 운영 DB에 쌓인 분봉을 backtest-db로 옮긴다. 백필·재생과
        // 무관한 데이터 이동이므로 단독 모드로 두고, 끝나면 바로 종료한다.
        if ("import-minutes".equalsIgnoreCase(mode)) {
            minuteCandleImporter.importAll();
            return true;
        }
        if ("events".equalsIgnoreCase(mode)) {
            // B-4: 공시 이벤트 유형별 반응 통계 (--backtest.mode=events)
            eventPipeline.run();
            return true;
        }
        if ("smoke".equalsIgnoreCase(mode)) {
            BacktestRunner.RunResult smoke = runner.run(
                    new BacktestRunner.RunConfig("SMOKE-K0.5", symbols, from, to));
            log.info("[Orchestrator] 스모크 결과: {}", smoke.metrics().summaryLine());
            return true;
        }
        return false;
    }

    /** 방식2·3(§13) 소급 검증과 Exit Lab(§14) — VB와 신호가 섞이지 않도록 하나만 켠다 */
    private boolean runStrategyMode(String mode, List<String> symbols, LocalDate from, LocalDate to) {
        if ("ma-breakout".equalsIgnoreCase(mode)) {
            singleStrategyLab.runSingle(
                    new LabScope("이동평균 정배열 돌파(MA_BREAKOUT)", "MA", symbols, from, to),
                    toggles::enableMaBreakoutOnly, List.of());
            return true;
        }
        if ("scalping".equalsIgnoreCase(mode)) {
            toggles.enableScalpingOnly();
            singleStrategyLab.runScalping(symbols, from, to);
            return true;
        }
        // Exit Lab (§14) — 진입 고정, 출구 프로필 스윕 (손익비 재설계)
        if ("exit-lab".equalsIgnoreCase(mode)) {
            exitLab.run(new LabScope("변동성 돌파(VB)", "VB", symbols, from, to),
                    toggles::enableVbOnly);
            exitLab.run(new LabScope("이동평균 정배열 돌파(MA_BREAKOUT)", "MA", symbols, from, to),
                    toggles::enableMaBreakoutOnly);
            return true;
        }
        // Donchian 돌파(전략1=VB 대체 후보, 2026-08) — 진입만 Donchian으로 바꿔 검증된
        // exit-lab(P0~P4) 스윕을 그대로 태운다. §14의 MA 검증 경로와 동일 절차로 비교.
        if ("donchian".equalsIgnoreCase(mode)) {
            exitLab.run(new LabScope("돈치안 돌파(DONCHIAN)", "DONCHIAN", symbols, from, to),
                    toggles::enableDonchianOnly);
            return true;
        }
        return false;
    }
}
