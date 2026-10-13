package com.trading.risk;

import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 매수 차단 한 건을 DB에 남긴다 (2026-09-22 신설).
 *
 * <p><b>중복 억제가 이 클래스의 핵심이다.</b> {@code TradingScheduler}는 1초마다 돌고
 * 유니버스를 라운드로빈으로 순회한다 — 같은 종목에 같은 룰이 계속 걸리면
 * 억제 없이는 하루 수천~수만 행이 쌓인다(1종목 단독이면 6.5시간 × 3,600초).
 *
 * <p><b>고른 방식: 창(window) 단위 합치기.</b> 같은 {@code (종목, 룰)}은 창(기본 10분)
 * 안에서 한 번만 저장하고, 그 사이에 몇 번 더 막혔는지는 메모리에 세뒀다가
 * <b>다음 저장 행의 {@code blockedCount}에 실어 보낸다</b>. 그래서
 * <ul>
 *   <li>행 수 상한이 예측 가능하다 — 종목 20개 × 6창/시간 × 6.5시간 ≈ 780행/일</li>
 *   <li>횟수를 잃지 않는다 — "몇 번 막았나"가 단순 건수 세기로 나온다
 *       (그냥 건너뛰기만 하면 집계가 실제보다 작게 나온다)</li>
 *   <li>첫 차단은 즉시 남는다 — 창이 끝나기를 기다렸다 쓰면, 드물게 한 번 막힌 건이
 *       앱이 꺼질 때 영영 기록되지 않는다</li>
 * </ul>
 *
 * <p>안전 설계는 {@link ModeTransitionRecorder}와 같다 — 예외를 전부 삼키고, 저장은
 * {@link RiskBlockWriter}(별도 빈, {@code REQUIRES_NEW})에 맡겨 <b>남의 트랜잭션을
 * 오염시키지 않으며</b>, backtest 프로필에는 빈을 만들지 않는다 (감사 24_audit M-1).
 */
@Component
@Profile("!backtest")
public class RiskBlockRecorder {

    private static final Logger log = LoggerFactory.getLogger(RiskBlockRecorder.class);

    /** 억제 상태 키 상한 — 종목 20 × 룰 14 = 280이 현실 최대치라 넉넉하다 */
    private static final int MAX_KEYS = 500;

    private final RiskBlockWriter writer;
    private final Clock clock;
    private final Duration window;

    private final Map<String, Suppression> byKey = new ConcurrentHashMap<>();

    public RiskBlockRecorder(RiskBlockWriter writer,
                             Clock clock,
                             @Value("${trading.risk-block.suppress-minutes:10}") int suppressMinutes) {
        this.writer = writer;
        this.clock = clock;
        this.window = Duration.ofMinutes(Math.max(0, suppressMinutes));
    }

    /** 창 하나의 상태 — 마지막으로 저장한 시각과, 그 뒤로 억제한 횟수 */
    private record Suppression(LocalDateTime lastSavedAt, int suppressed) {}

    /**
     * 차단 한 건 기록. 어떤 예외도 밖으로 내보내지 않는다 —
     * 기록이 실패한다고 매매 루프가 흔들리면 안 된다.
     */
    public void record(Signal signal, String reason) {
        try {
            saveIfWindowOpen(signal, reason);
        } catch (Exception e) {
            log.warn("[RiskBlock] 기록 실패 (매매 판정에는 영향 없음) — stockCode={}, reason={}: {}",
                    signal.getStockCode(), reason, e.toString());
        }
    }

    private void saveIfWindowOpen(Signal signal, String reason) {
        String ruleName = RiskRuleNameResolver.resolve(reason);
        int blockedCount = claim(signal.getStockCode() + "|" + ruleName, LocalDateTime.now(clock));
        if (blockedCount == 0) return;   // 창이 아직 안 지났다 — 세어두기만 했다

        writer.saveInNewTransaction(RiskBlockRecord.of(LocalDateTime.now(clock),
                signal.getStockCode(), ruleName, reason, signal.getStrategyName(), blockedCount));
    }

    /**
     * 저장해도 되는지 판정하고 상태를 갱신한다.
     * @return 0이면 억제(저장 안 함). 1 이상이면 그 값이 이번 행이 대표하는 차단 횟수.
     */
    private int claim(String key, LocalDateTime now) {
        if (byKey.size() > MAX_KEYS) {
            // 유니버스 교체 등으로 키가 계속 늘면 통째로 비운다 (기록은 부가 기능 — 메모리가 우선)
            log.warn("[RiskBlock] 억제 상태 키가 {}개를 넘어 초기화한다", MAX_KEYS);
            byKey.clear();
        }
        int[] decided = {0};
        byKey.compute(key, (k, previous) -> {
            if (previous != null && Duration.between(previous.lastSavedAt(), now).compareTo(window) < 0) {
                decided[0] = 0;
                return new Suppression(previous.lastSavedAt(), previous.suppressed() + 1);
            }
            decided[0] = (previous == null ? 0 : previous.suppressed()) + 1;
            return new Suppression(now, 0);
        });
        return decided[0];
    }
}
