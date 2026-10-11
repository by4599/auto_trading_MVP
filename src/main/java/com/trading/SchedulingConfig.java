package com.trading;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 월클럭 스케줄링 활성화 — backtest 프로파일 제외.
 *
 * 백테스트는 MutableClock으로 시간을 재생하는데, @Scheduled(실제 시계)가 함께 돌면
 * ShadowPortfolio.tick() 등이 재생 스레드와 경쟁해 결정성이 깨진다
 * (실사고: daily_equity 동시 삽입 PK 충돌). 백테스트에서 tick()은 BacktestRunner가
 * 봉마다 직접 호출한다.
 *
 * 스케줄러 2개 (2026-10-10, BACKLOG [2026-09-21]):
 *   - taskScheduler   (풀 1, "scheduling-") — 이름 없는 @Scheduled 전부. 손절·강제청산·주문·체결·
 *     잔고 대조가 지금처럼 한 스레드에서 순서대로 돈다. 이 직렬성이 포지션·잔고 캐시·운전 모드를 지킨다.
 *   - ioTaskScheduler (풀 2, "io-sched-")  — KIS도 매매 상태도 건드리지 않는 느린 외부 I/O만.
 *     @Scheduled(scheduler = IO_SCHEDULER)로 옮긴 뉴스·공시 수집, 그리고 하트비트·미러가 넘기는 HTTP 업로드.
 *     2스레드라 서로 다른 I/O 작업끼리는 동시에 돌 수 있다(같은 메서드의 중복 실행은 스프링이 막는다).
 *
 * ⚠ taskScheduler를 지우거나 이름을 바꾸지 말 것. TaskScheduler 빈이 하나라도 있으면 스프링 부트의
 *   자동 스케줄러가 물러나고, 남은 빈이 ioTaskScheduler 하나뿐이면 그것이 모든 @Scheduled의 기본이 된다
 *   → 매매 감시들이 2스레드에서 서로 동시에 돌기 시작한다. 기본 선택 규칙은 "유일한 TaskScheduler 빈,
 *   여럿이면 이름이 taskScheduler인 빈"이다(Spring 6.1 TaskSchedulerRouter). 회귀는 SchedulingConfigTest가 잡는다.
 * ⚠ spring.task.scheduling.* 속성은 이제 이 풀에 적용되지 않는다 — 풀 크기 1은 일부러 코드에 고정했다.
 * 부수효과: ThreadPoolTaskScheduler는 Executor이기도 해서 부트의 applicationTaskExecutor(@Async·MVC 비동기용)가
 *   물러난다. 이 앱은 둘 다 쓰지 않는다(2026-10-10 확인) — 나중에 @Async를 쓰려면 실행기를 따로 정의할 것.
 */
@Configuration
@EnableScheduling
@Profile("!backtest")
public class SchedulingConfig {

    /** 기본 스케줄러 빈 이름 — 스프링이 이 이름으로 기본을 고른다(바꾸면 기본 선택이 깨진다) */
    public static final String DEFAULT_SCHEDULER = "taskScheduler";

    /** 느린 외부 I/O 전용 — {@code @Scheduled(scheduler = IO_SCHEDULER)} 또는 {@code @Qualifier}로 실행기 주입 */
    public static final String IO_SCHEDULER = "ioTaskScheduler";

    @Bean(name = DEFAULT_SCHEDULER)
    public ThreadPoolTaskScheduler taskScheduler() {
        return scheduler(1, "scheduling-");   // 운영 로그의 [scheduling-1] 유지
    }

    @Bean(name = IO_SCHEDULER)
    public ThreadPoolTaskScheduler ioTaskScheduler() {
        return scheduler(2, "io-sched-");
    }

    private static ThreadPoolTaskScheduler scheduler(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        return scheduler;
    }
}
