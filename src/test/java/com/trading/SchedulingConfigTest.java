package com.trading;

import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.mirror.MirrorPublisher;
import com.trading.mirror.MirrorSnapshotAssembler;
import com.trading.mirror.SupabaseMirrorScheduler;
import com.trading.mirror.SupabaseProperties;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultRepository;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 스케줄러 배선 — 실제 스프링 컨테이너로 "어느 @Scheduled가 어느 스레드에서 도는가"를 고정한다.
 *
 * 지키려는 함정: TaskScheduler 빈을 하나라도 정의하면 스프링 부트 자동 스케줄러가 물러나고,
 * 남은 빈이 하나뿐이면 그것이 모든 @Scheduled의 기본이 된다. I/O 풀(2스레드)만 정의하면 손절·
 * 강제청산 감시가 서로 동시에 돌기 시작한다 — 그래서 기본 스케줄러(풀 1)를 명시적으로 둔다.
 * 운영과 같게 부트 자동 설정(TaskSchedulingAutoConfiguration)까지 얹어 그것이 물러나는지도 본다.
 */
@DisplayName("스케줄러 배선 — 이름 없는 @Scheduled는 기본 1스레드, 지정한 것만 I/O 풀")
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    @DisplayName("TaskScheduler는 우리가 만든 2개뿐(부트 자동 스케줄러는 물러남) — 기본은 풀 1·'scheduling-', I/O는 풀 2·'io-sched-'")
    void exactly_two_schedulers_with_fixed_sizes() {
        runner.run(ctx -> {
            assertThat(ctx.getBeansOfType(TaskScheduler.class))
                    .containsOnlyKeys(SchedulingConfig.DEFAULT_SCHEDULER, SchedulingConfig.IO_SCHEDULER);

            ThreadPoolTaskScheduler main = ctx.getBean(SchedulingConfig.DEFAULT_SCHEDULER, ThreadPoolTaskScheduler.class);
            assertThat(main.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(main.getThreadNamePrefix()).isEqualTo("scheduling-");   // 운영 로그 [scheduling-1] 유지

            ThreadPoolTaskScheduler io = ctx.getBean(SchedulingConfig.IO_SCHEDULER, ThreadPoolTaskScheduler.class);
            assertThat(io.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(io.getThreadNamePrefix()).isEqualTo("io-sched-");
        });
    }

    @Test
    @DisplayName("이름 없는 @Scheduled는 전부 'scheduling-' 스레드 하나에서, 서로 겹치지 않고 순서대로 돈다")
    void unnamed_tasks_run_serially_on_the_single_default_thread() {
        runner.withBean(Probe.class).run(ctx -> {
            Probe probe = ctx.getBean(Probe.class);

            assertThat(probe.defaultRuns.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.defaultThreads).hasSize(1)
                    .allSatisfy(name -> assertThat(name).startsWith("scheduling-"));
            assertThat(probe.maxConcurrentDefault.get()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("scheduler = IO_SCHEDULER로 지정한 작업만 'io-sched-' 스레드에서 돈다")
    void named_tasks_run_on_the_io_pool() {
        runner.withBean(Probe.class).run(ctx -> {
            Probe probe = ctx.getBean(Probe.class);

            assertThat(probe.ioRuns.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.ioThreads).isNotEmpty().allSatisfy(name -> assertThat(name).startsWith("io-sched-"));
        });
    }

    @Test
    @DisplayName("I/O 작업이 영영 안 끝나도 기본 스레드의 감시는 계속 돈다 — 뉴스·공시가 손절을 멈추지 못한다")
    void stuck_io_task_does_not_stall_default_tasks() {
        runner.withBean(StuckIoProbe.class).run(ctx -> {
            StuckIoProbe probe = ctx.getBean(StuckIoProbe.class);
            try {
                assertThat(probe.stuckEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(probe.ticks.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                probe.release.countDown();
            }
        });
    }

    @Test
    @DisplayName("backtest 프로필에는 스케줄러 빈도 @Scheduled 처리기도 없다 — 백테스트 결정성 보존")
    void backtest_profile_has_no_schedulers() {
        runner.withPropertyValues("spring.profiles.active=backtest")
                .withBean(Probe.class)
                .run(ctx -> {
                    assertThat(ctx.getBeansOfType(TaskScheduler.class)).isEmpty();
                    assertThat(ctx.containsBean(
                            "org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();
                });
    }

    @Test
    @DisplayName("paper 배선 — 하트비트·미러는 실행기로 ioTaskScheduler를 받는다 (Executor 빈이 2개라 이름표가 틀리면 기동이 깨진다)")
    void paper_heartbeat_and_mirror_receive_the_io_scheduler() {
        Clock clock = Clock.systemDefaultZone();
        // 프로필은 속성으로 — withBean 등록이 초기화기보다 먼저 적용돼 @Profile("paper") 빈이 빠지는 것을 막는다
        runner.withPropertyValues("spring.profiles.active=paper")
                .withBean(HeartbeatProperties.class)
                .withBean(TradingStatusManager.class)
                .withBean(PositionRepository.class, () -> mock(PositionRepository.class))
                .withBean(SupabaseProperties.class)
                .withBean(MirrorPublisher.class, () -> mock(MirrorPublisher.class))
                .withBean(MarketCalendarService.class,
                        () -> new MarketCalendarService(new MarketCalendarProperties(), clock))
                .withBean(MirrorSnapshotAssembler.class, () -> new MirrorSnapshotAssembler(
                        mock(PositionManager.class), mock(PositionRepository.class), mock(TradeResultRepository.class),
                        mock(PortfolioStateRepository.class), new TradingStatusManager(), new SupabaseProperties(), clock))
                .withUserConfiguration(DeadmanHeartbeat.class, SupabaseMirrorScheduler.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    Object io = ctx.getBean(SchedulingConfig.IO_SCHEDULER);
                    assertThat(ReflectionTestUtils.getField(ctx.getBean(DeadmanHeartbeat.class), "ioExecutor"))
                            .isSameAs(io);
                    assertThat(ReflectionTestUtils.getField(ctx.getBean(SupabaseMirrorScheduler.class), "uploadExecutor"))
                            .isSameAs(io);
                });
    }

    /** 이름 없는 작업 둘(겹치는지 보려고 잠깐씩 붙잡는다) + I/O로 지정한 작업 하나 */
    static class Probe {
        final CountDownLatch defaultRuns = new CountDownLatch(10);
        final CountDownLatch ioRuns = new CountDownLatch(3);
        final Set<String> defaultThreads = ConcurrentHashMap.newKeySet();
        final Set<String> ioThreads = ConcurrentHashMap.newKeySet();
        final AtomicInteger activeDefault = new AtomicInteger();
        final AtomicInteger maxConcurrentDefault = new AtomicInteger();

        @Scheduled(fixedDelay = 10)
        void firstMonitor() {
            recordDefault();
        }

        @Scheduled(fixedDelay = 10)
        void secondMonitor() {
            recordDefault();
        }

        @Scheduled(fixedDelay = 10, scheduler = SchedulingConfig.IO_SCHEDULER)
        void slowIo() {
            ioThreads.add(Thread.currentThread().getName());
            ioRuns.countDown();
        }

        private void recordDefault() {
            maxConcurrentDefault.accumulateAndGet(activeDefault.incrementAndGet(), Math::max);
            defaultThreads.add(Thread.currentThread().getName());
            sleepQuietly(20);   // 겹칠 기회를 준다 — 풀이 2 이상이면 여기서 동시 실행이 드러난다
            activeDefault.decrementAndGet();
            defaultRuns.countDown();
        }
    }

    /** I/O 쪽에서 영영 안 끝나는 작업(타임아웃 없는 HTTP 흉내) + 기본 쪽 1초 감시 흉내 */
    static class StuckIoProbe {
        final CountDownLatch stuckEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch ticks = new CountDownLatch(20);

        @Scheduled(fixedDelay = 10, scheduler = SchedulingConfig.IO_SCHEDULER)
        void hangingFetch() throws InterruptedException {
            stuckEntered.countDown();
            release.await();
        }

        @Scheduled(fixedDelay = 10)
        void monitor() {
            ticks.countDown();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
