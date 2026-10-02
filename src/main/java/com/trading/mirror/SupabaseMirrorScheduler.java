package com.trading.mirror;

import com.trading.market.MarketCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 운영 스냅샷을 주기적으로 Supabase에 복사한다 (기본 5분).
 *
 * 거래일 장중(09:00~15:30)에만 보낸다. 두 가지 이유다:
 *   1) 장외에는 잔고 API가 미러 때문에만 호출되고, 그 실패가 KisApiClient의 연속 실패
 *      카운터에 쌓여 아침을 SAFE_MODE로 시작하게 만들 수 있다 — 미러가 매매를 막는 셈이다.
 *   2) 장중에는 RiskMonitor가 이미 1초마다 잔고를 받아두므로 3초 캐시에 얹혀 공짜로 읽는다.
 * 장 마감 후에는 마지막 스냅샷이 updated_at과 함께 그대로 남아 종료 시점 상태를 보여준다.
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

    public SupabaseMirrorScheduler(MirrorSnapshotAssembler assembler,
                                   MirrorPublisher         publisher,
                                   MarketCalendarService   marketCalendar,
                                   SupabaseProperties      props) {
        this.assembler      = assembler;
        this.publisher      = publisher;
        this.marketCalendar = marketCalendar;
        this.props          = props;
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
            publisher.publish(assembler.assemble());
        } catch (Exception e) {
            log.warn("[미러] 스냅샷 조립 실패 — 매매에는 영향 없음: {}", e.getMessage());
        }
    }
}
