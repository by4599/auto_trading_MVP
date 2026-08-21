package com.trading.backtest;

import com.trading.research.EventTypeStat;
import com.trading.research.EventTypeStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * B-4 ③ event_type_registry 갱신 — status 보존, 통계 조건 충족 유형만 CANDIDATE 표기.
 *
 * <p><b>PROMOTED 승격은 사람만 한다(게이트 G2)</b> — 이 클래스는 절대 승격시키지 않는다.
 */
@Component
@Profile("backtest")
public class EventRegistryUpdater {

    private static final Logger log = LoggerFactory.getLogger(EventRegistryUpdater.class);

    /** event_type 컬럼 길이 — 그룹 접미사·긴 테마명 방어 */
    private static final int MAX_KEY_LENGTH = 30;

    private final EventTypeStatRepository registryRepository;

    public EventRegistryUpdater(EventTypeStatRepository registryRepository) {
        this.registryRepository = registryRepository;
    }

    public void updateRegistry(List<EventStatsBacktester.EventStat> stats) {
        for (EventStatsBacktester.EventStat s : stats) {
            if (tooLong(s.eventType())) continue;
            EventTypeStat row = registryRepository.findById(s.eventType())
                    .orElseGet(() -> EventTypeStat.recorded(s.eventType()));
            row.updateStats(s.samples(), s.winRateD5(),
                    s.at(1).median(), s.at(5).median(), s.at(10).median(), s.at(20).median(),
                    s.at(5).p25(), s.at(20).p25());
            if (EventCandidateCriteria.meets(s)) {
                row.markCandidate();
            }
            registryRepository.save(row);
        }
        log.info("[EventBacktest] event_type_registry {}개 유형 갱신", stats.size());
    }

    /** 파급 통계도 레지스트리에 기록 — 키 "SPILL:테마:유형" (승격은 동일하게 사람만, 게이트 G2) */
    public void updateSpilloverRegistry(List<SpilloverStatsBacktester.SpilloverStat> stats) {
        for (SpilloverStatsBacktester.SpilloverStat s : stats) {
            String key = s.registryKey();
            if (tooLong(key)) continue;
            EventTypeStat row = registryRepository.findById(key)
                    .orElseGet(() -> EventTypeStat.recorded(key));
            row.updateStats(s.samples(), s.winRateD5(),
                    s.at(1).median(), s.at(5).median(), s.at(10).median(), s.at(20).median(),
                    s.at(5).p25(), s.at(20).p25());
            if (EventCandidateCriteria.meets(s.samples(), s.at(5))) {
                row.markCandidate();
            }
            registryRepository.save(row);
        }
        if (!stats.isEmpty()) {
            log.info("[EventBacktest] 파급 통계 {}개(테마×유형) 레지스트리 갱신", stats.size());
        }
    }

    private static boolean tooLong(String key) {
        if (key.length() <= MAX_KEY_LENGTH) return false;
        log.warn("[EventBacktest] 레지스트리 키 30자 초과 — 저장 건너뜀: {}", key);
        return true;
    }
}
