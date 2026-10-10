package com.trading.mirror;

import com.trading.SchedulingConfig;
import com.trading.market.MarketCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 운영 스냅샷을 주기적으로 Supabase에 복사한다 (기본 5분).
 *
 * 거래일 장중(09:00~15:30)에만 보낸다. 두 가지 이유다:
 *   1) 장외에는 잔고 API가 미러 때문에만 호출되고, 그 실패가 KisApiClient의 연속 실패
 *      카운터에 쌓여 아침을 SAFE_MODE로 시작하게 만들 수 있다 — 미러가 매매를 막는 셈이다.
 *   2) 장중에는 RiskMonitor가 이미 1초마다 잔고를 받아두므로 3초 캐시에 얹혀 공짜로 읽는다.
 * 장 마감 후에는 마지막 스냅샷이 updated_at과 함께 그대로 남아 종료 시점 상태를 보여준다.
 *
 * 스레드 배치 (2026-10-10, BACKLOG [2026-09-21]): 조립은 <b>기본 스케줄러 스레드</b>, 업로드만 I/O 스레드.
 * 조립이 부르는 {@code PositionManager.snapshotAccount()}는 캐시가 낡았으면 KIS 잔고 API를 부르고
 * 잔고 캐시를 갱신한다 — 매매 상태를 건드리는 일이라 감시들과 같은 스레드에서 순서대로 해야 한다.
 * 느린 것은 Supabase HTTP(최대 3+5초)뿐이므로 그것만 넘긴다.
 *
 * 어떤 예외도 밖으로 내보내지 않는다 — 미러 실패가 매매 스케줄을 흔들면 안 된다.
 */
@Component
@Profile("paper")
public class SupabaseMirrorScheduler {

    private static final Logger log = LoggerFactory.getLogger(SupabaseMirrorScheduler.class);

    private final MirrorSnapshotAssembler assembler;
    private final MirrorPublisher         publisher;
    private final MarketCalendarService   marketCalendar;
    private final SupabaseProperties      props;
    private final Executor                uploadExecutor;

    public SupabaseMirrorScheduler(MirrorSnapshotAssembler assembler,
                                   MirrorPublisher         publisher,
                                   MarketCalendarService   marketCalendar,
                                   SupabaseProperties      props,
                                   @Qualifier(SchedulingConfig.IO_SCHEDULER) Executor uploadExecutor) {
        this.assembler      = assembler;
        this.publisher      = publisher;
        this.marketCalendar = marketCalendar;
        this.props          = props;
        this.uploadExecutor = uploadExecutor;
    }

    @Scheduled(fixedDelayString   = "${supabase.interval-ms:300000}",
               initialDelayString = "${supabase.interval-ms:300000}")
    public void push() {
        if (!props.isConfigured()) return;
        if (!marketCalendar.isDuringMarketHoursNow()) {
            log.debug("[미러] 장외 시간 — 전송 스킵");
            return;
        }
        try {
            MirrorSnapshot snapshot = assembler.assemble();             // 잔고 캐시·KIS — 이 스레드에서만
            uploadExecutor.execute(() -> publisher.publish(snapshot));   // 느린 HTTP — I/O 스레드로
        } catch (Exception e) {
            log.warn("[미러] 스냅샷 조립 또는 업로드 위임 실패 — 매매에는 영향 없음: {}", e.getMessage());
        }
    }
}
