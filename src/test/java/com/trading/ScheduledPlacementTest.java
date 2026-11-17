package com.trading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 코드의 {@code @Scheduled} 배치 감사 — 누가 I/O 풀로 가고 누가 기본 1스레드에 남는가.
 *
 * 규칙: KIS를 부르거나 매매 상태(포지션·잔고 캐시·운전 모드 쓰기)를 건드리는 작업은 기본 스레드에
 * 남는다(직렬성 = 동시 호출 위험 회피). I/O 풀은 그 어느 것도 하지 않는 느린 외부 수집만.
 * 손절·청산 감시를 실수로 I/O 풀에 올리면(또는 뉴스를 다시 기본으로 내리면) 여기서 걸린다.
 * 프로필 조건을 무시하고 스캔한다 — paper·backtest 전용 빈도 감사 대상이다.
 */
@DisplayName("@Scheduled 배치 감사 — I/O 풀은 뉴스·공시 3개뿐, 손절·청산·매매 감시는 전부 기본 1스레드")
class ScheduledPlacementTest {

    /** I/O 풀로 옮긴 작업 — 외부 HTTP + 자기 테이블 쓰기뿐, KIS·매매 상태 무관 */
    private static final Set<String> IO_TASKS = Set.of(
            "NewsAggregatorService#aggregate",
            "NewsAggregatorService#cleanOld",
            "DartDisclosureService#scheduledAggregate");

    /**
     * 반드시 기본 스레드에 남아야 하는 것 — 손절·강제청산·주문·체결·잔고 대조, 그리고
     * 하트비트·미러의 트리거(느린 HTTP만 I/O로 넘기고 판단·조립은 여기서 한다).
     */
    private static final Set<String> MUST_STAY_DEFAULT = Set.of(
            "TradingScheduler#run",
            "RiskMonitor#monitor",
            "StopLossMonitor#monitor",
            "FillPoller#pollFills",
            "ShadowPortfolioTicker#tick",
            "ShadowPortfolioReconciler#reconcile",
            "TimeCutScheduler#run",
            "MaxHoldScheduler#run",
            "LiquidationDrillScheduler#tick",
            "DailyPnlRecorder#recordClose",
            "KisIndexRegimeSource#scheduleRefresh",
            "MinuteCandleCollector#collectToday",
            "DeadmanHeartbeat#ping",
            "SupabaseMirrorScheduler#push");

    @Test
    @DisplayName("I/O 풀로 지정된 @Scheduled는 정확히 뉴스 2개 + 공시 1개다")
    void io_pool_hosts_exactly_news_and_disclosure() throws Exception {
        Map<String, Set<String>> placement = scheduledPlacement();

        assertThat(placement.keySet()).containsAll(IO_TASKS).containsAll(MUST_STAY_DEFAULT);   // 스캔 누락 방지
        Set<String> onIo = placement.entrySet().stream()
                .filter(entry -> entry.getValue().contains(SchedulingConfig.IO_SCHEDULER))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        assertThat(onIo).isEqualTo(IO_TASKS);
    }

    @Test
    @DisplayName("나머지는 전부 이름 없는(=기본 1스레드) @Scheduled다 — 다른 스케줄러 이름은 없다")
    void everything_else_stays_on_the_default_scheduler() throws Exception {
        Map<String, Set<String>> placement = scheduledPlacement();

        placement.forEach((method, schedulers) -> {
            Set<String> expected = IO_TASKS.contains(method) ? Set.of(SchedulingConfig.IO_SCHEDULER) : Set.of("");
            assertThat(schedulers).as(method).isEqualTo(expected);
        });
    }

    /** "클래스#메서드" → 그 메서드의 @Scheduled가 고른 스케줄러 이름들 (운영 클래스만) */
    private static Map<String, Set<String>> scheduledPlacement() throws ClassNotFoundException {
        URL mainOutput = TradingApplication.class.getProtectionDomain().getCodeSource().getLocation();
        ClassLoader loader = TradingApplication.class.getClassLoader();
        Map<String, Set<String>> placement = new TreeMap<>();
        for (BeanDefinition candidate : componentScanner().findCandidateComponents("com.trading")) {
            Class<?> type = Class.forName(candidate.getBeanClassName(), false, loader);
            if (!mainOutput.equals(type.getProtectionDomain().getCodeSource().getLocation())) continue;   // 테스트 클래스 제외
            for (Method method : type.getDeclaredMethods()) {
                Set<Scheduled> annotations = AnnotatedElementUtils.getMergedRepeatableAnnotations(method, Scheduled.class);
                if (annotations.isEmpty()) continue;
                placement.put(type.getSimpleName() + "#" + method.getName(),
                        annotations.stream().map(Scheduled::scheduler).collect(Collectors.toSet()));
            }
        }
        return placement;
    }

    /** @Component 계열이면 @Profile 조건과 무관하게 전부 고른다 */
    private static ClassPathScanningCandidateComponentProvider componentScanner() {
        TypeFilter components = new AnnotationTypeFilter(Component.class);
        return new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                return components.match(reader, getMetadataReaderFactory());
            }
        };
    }
}
