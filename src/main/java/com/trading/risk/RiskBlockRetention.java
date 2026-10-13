package com.trading.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 매수 차단 이력 보존 기간 정리 (2026-09-22). {@code MinuteCandleRetention}과 같은 방식이다.
 *
 * <p>차단 이력은 {@link RiskBlockRecorder}의 창 합치기 덕분에 하루 최대 수백 행이지만
 * 그대로 두면 해마다 수만 행이 된다. 진단용 기록이므로 기본 90일만 남긴다 —
 * 7거래일 정지를 못 본 사고가 한 분기 안에서 되짚을 수 있으면 충분하다.
 *
 * <p>모드 전환 이력({@code mode_transition})은 <b>정리하지 않는다</b>. 하루 몇 건뿐이라
 * 몇 년을 모아도 수천 행이고, "언제 멈췄나"는 오래된 것일수록 값어치가 있다.
 *
 * <p>프로필이 {@code !backtest}인 이유: 기록하는 쪽({@link RiskBlockRecorder})이 그 범위라
 * {@code paper}로 좁히면 <b>real에서는 쌓이기만 하고 영영 안 지워진다</b> (감사 24_audit L-2).
 */
@Component
@Profile("!backtest")
public class RiskBlockRetention {

    private static final Logger log = LoggerFactory.getLogger(RiskBlockRetention.class);

    private final RiskBlockRecordRepository repository;
    private final Clock clock;
    private final int retentionDays;

    public RiskBlockRetention(RiskBlockRecordRepository repository,
                              Clock clock,
                              @Value("${trading.risk-block.retention-days:90}") int retentionDays) {
        this.repository = repository;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /** 매일 23:20 KST — 분봉 정리(23:10) 다음 순서 */
    @Scheduled(cron = "0 20 23 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeOldBlocks() {
        if (retentionDays <= 0) {
            log.info("[RiskBlockRetention] 보존 기간 무제한 설정 — 정리 생략");
            return;
        }
        LocalDateTime cutoff = LocalDate.now(clock).minusDays(retentionDays).atStartOfDay();

        long candidates = repository.countByOccurredAtBefore(cutoff);
        if (candidates == 0) {
            log.info("[RiskBlockRetention] {} 이전 차단 이력 없음 — 정리 생략", cutoff.toLocalDate());
            return;
        }

        long deleted = repository.deleteByOccurredAtBefore(cutoff);
        log.info("[RiskBlockRetention] {} 이전 차단 이력 {}건 삭제 (보존 {}일)",
                cutoff.toLocalDate(), deleted, retentionDays);
    }
}
