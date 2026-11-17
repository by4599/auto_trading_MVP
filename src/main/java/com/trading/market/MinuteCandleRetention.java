package com.trading.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 운영 DB의 분봉 보존 기간 정리 (2026-08-04).
 *
 * 분봉은 하루 약 4.7MB씩 쌓인다(55종목 × 391분, 2026-08-04 실측: 0.21MB → 4.90MB).
 * 연 1.2GB 규모라 잔고·주문이 들어 있는 운영 DB를 계속 불린다. 분봉의 소비처는
 * 백테스트뿐이고 {@code --backtest.mode=import-minutes}로 backtest-db에 이관하므로,
 * 운영 DB에는 최근 구간만 남긴다.
 *
 * <b>일봉은 절대 지우지 않는다</b> — timeframe을 MINUTE으로 못박아 조회·삭제한다.
 * 보존 기간을 넉넉히(기본 60일) 잡은 이유는, 이관이 몇 주 늦어져도 원본이 살아 있게
 * 하기 위해서다. KIS는 과거 분봉을 다시 주지 않으므로 이관 전 삭제는 영구 손실이다.
 */
@Component
@Profile("paper")
public class MinuteCandleRetention {

    private static final Logger log = LoggerFactory.getLogger(MinuteCandleRetention.class);

    private final CandleHistoryRepository repository;
    private final Clock clock;
    private final int retentionDays;

    public MinuteCandleRetention(CandleHistoryRepository repository,
                                 Clock clock,
                                 @Value("${trading.minute-retention-days:60}") int retentionDays) {
        this.repository = repository;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /** 매일 23:10 KST — 당일 수집·캐치업(최대 22:45)이 모두 끝난 뒤 */
    @Scheduled(cron = "0 10 23 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeOldMinutes() {
        if (retentionDays <= 0) {
            log.info("[MinuteRetention] 보존 기간 무제한 설정 — 정리 생략");
            return;
        }
        LocalDate cutoff = LocalDate.now(clock).minusDays(retentionDays);

        long candidates = repository.countByTimeframeAndCandleDateBefore(Timeframe.MINUTE, cutoff);
        if (candidates == 0) {
            log.info("[MinuteRetention] {} 이전 분봉 없음 — 정리 생략", cutoff);
            return;
        }

        long deleted = repository.deleteByTimeframeAndCandleDateBefore(Timeframe.MINUTE, cutoff);
        log.warn("[MinuteRetention] {} 이전 분봉 {}건 삭제 (보존 {}일). "
                        + "backtest-db 이관(import-minutes)을 이 주기 안에 돌려야 표본이 보존된다",
                cutoff, deleted, retentionDays);
    }
}
