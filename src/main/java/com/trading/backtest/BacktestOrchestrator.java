package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 백테스트 진입점 (B-3) — --spring.profiles.active=backtest 로 부팅 시 실행.
 *
 * <p>이 클래스는 <b>실행과 종료</b>만 책임진다. 모드 선택·랩 실행은
 * {@link BacktestModeRunner}가 맡는다.
 */
@Component
@Profile("backtest")
public class BacktestOrchestrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestOrchestrator.class);

    private final BacktestModeRunner modeRunner;
    private final ConfigurableApplicationContext context;

    public BacktestOrchestrator(BacktestModeRunner modeRunner,
                                ConfigurableApplicationContext context) {
        this.modeRunner = modeRunner;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        int exitCode = 0;
        try {
            modeRunner.execute();
        } catch (Exception e) {
            log.error("[Orchestrator] 백테스트 실패", e);
            exitCode = 1;
        }
        // 스케줄러 스레드(비데몬)가 JVM을 붙잡으므로 명시 종료
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
