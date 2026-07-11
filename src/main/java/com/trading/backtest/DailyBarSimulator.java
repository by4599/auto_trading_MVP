package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.order.OrderEngine;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskResult;
import com.trading.risk.TrailingStopTracker;
import com.trading.signal.Signal;
import com.trading.signal.SignalDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 일봉 근사 하루 시퀀스 (B-2, 계획 D6 — 애매하면 항상 불리하게).
 *
 * ① 09:05 보유분 갭다운 손절 (시가 ≤ 손절가 → 시가 체결)
 * ② 10:00 보유분 장중 손절 (저가 ≤ 손절가 → 손절가 체결)
 * ③ 10:00 진입 판정 — 시뮬가를 당일 고가로 놓고 전략 발화 여부 확인.
 *    발화 시 진입가는 이분탐색으로 복원한 돌파 크로싱가 (갭 상승이면 시가).
 *    전략을 블랙박스로 유지한다: K 로직을 복제하지 않고 evaluate() 단조성만 이용.
 * ④ 당일 진입분 손절 — 저가 ≤ 손절가면 체결로 간주 (저가 발생 시점이 진입 전이었을
 *    가능성은 무시하는 비관적 관례)
 * 타임컷(15:15)·일마감은 BacktestRunner가 담당.
 *
 * 진입 체크포인트 10:00 고정은 일봉의 한계(돌파 시각 불명)에 따른 근사 가정 — 리포트 명시.
 */
@Component
@Profile("backtest")
public class DailyBarSimulator {

    private static final Logger log = LoggerFactory.getLogger(DailyBarSimulator.class);

    static final String EXIT_STOP_GAP   = "StopLoss-GapOpen";
    static final String EXIT_STOP_INTRA = "StopLoss-ATR";

    private final BacktestMarketDataService market;
    private final SignalDispatcher signalDispatcher;
    private final RiskEngine riskEngine;
    private final OrderEngine orderEngine;
    private final PositionRepository positionRepository;
    private final PositionManager positionManager;
    private final BacktestOrderClient orderClient;
    private final TrailingStopTracker trailingStopTracker;
    private final MutableClock clock;

    public DailyBarSimulator(BacktestMarketDataService market,
                             SignalDispatcher signalDispatcher,
                             RiskEngine riskEngine,
                             OrderEngine orderEngine,
                             PositionRepository positionRepository,
                             PositionManager positionManager,
                             BacktestOrderClient orderClient,
                             TrailingStopTracker trailingStopTracker,
                             MutableClock clock) {
        this.market = market;
        this.signalDispatcher = signalDispatcher;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.positionRepository = positionRepository;
        this.positionManager = positionManager;
        this.orderClient = orderClient;
        this.trailingStopTracker = trailingStopTracker;
        this.clock = clock;
    }

    public void simulateDay(String stockCode, LocalDate date) {
        Candle bar = market.dayBar(stockCode, date);
        if (bar == null) return; // 거래정지/데이터 공백 — 포지션 이월
        if (market.previousCandle(stockCode, date) == null) return; // 시리즈 첫날 — 전일 없음

        checkCarriedStop(stockCode, date, bar);
        checkEntry(stockCode, date, bar);
        checkSameDayStop(stockCode, date, bar);
        checkTrailingStop(stockCode, date, bar);
    }

    // ── ①② 이월 보유분 손절 ─────────────────────────────────────────────────

    private void checkCarriedStop(String stockCode, LocalDate date, Candle bar) {
        Position pos = heldPosition(stockCode);
        if (pos == null || pos.getStopPrice() == null) return;

        if (bar.getOpen() <= pos.getStopPrice()) {
            clock.setTo(date, LocalTime.of(9, 5));
            exitAt(stockCode, bar.getOpen(), EXIT_STOP_GAP);
        } else if (bar.getLow() <= pos.getStopPrice()) {
            clock.setTo(date, LocalTime.of(10, 0));
            exitAt(stockCode, pos.getStopPrice(), EXIT_STOP_INTRA);
        }
    }

    // ── ③ 진입 ────────────────────────────────────────────────────────────────

    private void checkEntry(String stockCode, LocalDate date, Candle bar) {
        clock.setTo(date, LocalTime.of(10, 0));

        // 당일 도달 최고가 기준으로 "이 날 발화했는가"를 판정
        market.setSimPrice(stockCode, bar.getHigh());
        List<Signal> signals = signalDispatcher.dispatch(
                stockCode, market.getRecentCandles(stockCode));
        Signal buy = signals.stream().filter(Signal::isBuy).findFirst().orElse(null);
        if (buy == null) return;

        double entryPrice = resolveEntryPrice(stockCode, date, bar);
        market.setSimPrice(stockCode, entryPrice);

        RiskResult result = riskEngine.check(buy, positionManager.snapshotAccount());
        if (!result.isPass()) {
            log.debug("[Sim] {} {} 매수 거부: {}", date, stockCode, result.getReason());
            return;
        }
        orderEngine.execute(buy);
    }

    /**
     * 발화 임계가(돌파가) 복원 — 전략은 가격에 단조이므로 [시가, 고가] 이분탐색.
     * 시가에서 이미 발화하면 갭 상승 진입 = 시가.
     */
    double resolveEntryPrice(String stockCode, LocalDate date, Candle bar) {
        if (firesAt(stockCode, date, bar, bar.getOpen())) {
            return bar.getOpen(); // 갭 상승 — 시가 진입
        }
        double lo = bar.getOpen();   // 미발화
        double hi = bar.getHigh();   // 발화
        for (int i = 0; i < 40 && hi - lo > 0.01; i++) {
            double mid = (lo + hi) / 2;
            if (firesAt(stockCode, date, bar, mid)) hi = mid;
            else                                    lo = mid;
        }
        return hi; // 최초 발화 가격 ≈ 돌파가
    }

    private boolean firesAt(String stockCode, LocalDate date, Candle bar, double price) {
        Candle yesterday = market.previousCandle(stockCode, date);
        Candle synthetic = new Candle(date,
                bar.getOpen(),
                Math.max(bar.getOpen(), price),
                Math.min(bar.getOpen(), price),
                price,
                bar.volume());
        return signalDispatcher.dispatch(stockCode, List.of(yesterday, synthetic)).stream()
                .anyMatch(Signal::isBuy);
    }

    // ── ④ 당일 진입분 손절 (비관적) ──────────────────────────────────────────

    private void checkSameDayStop(String stockCode, LocalDate date, Candle bar) {
        Position pos = heldPosition(stockCode);
        if (pos == null || pos.getStopPrice() == null) return;
        if (bar.getLow() > pos.getStopPrice()) return;

        clock.setTo(date, LocalTime.of(14, 0));
        exitAt(stockCode, pos.getStopPrice(), EXIT_STOP_INTRA);
    }

    // ── ⑤ 트레일링 스톱 필터 (§3.3, 기본 OFF — A/B 변형에서만 켠다) ──────────
    //
    // 일봉 근사의 정당성: 진입은 돌파가 최초 크로싱 시점이므로 당일 고가는 항상
    // 진입 이후에 성립한다. 종가 ≤ 트레일 레벨이면 고가→종가 경로에서 반드시
    // 레벨을 위에서 아래로 지났으므로 트레일 체결가는 레벨 그 자체다.

    private void checkTrailingStop(String stockCode, LocalDate date, Candle bar) {
        Position pos = heldPosition(stockCode);
        if (pos == null) return;

        trailingStopTracker.updateHigh(stockCode, bar.getHigh());
        trailingStopTracker.exitPrice(stockCode, bar.getClose(), pos.getAveragePrice())
                .ifPresent(level -> {
                    clock.setTo(date, LocalTime.of(14, 30));
                    exitAt(stockCode, level, "TrailingStop");
                });
    }

    // ── 공용 청산 경로 (Signal → RiskEngine → OrderEngine — ADR 규칙 5) ───────

    void exitAt(String stockCode, double price, String reason) {
        market.setSimPrice(stockCode, price);
        orderClient.setExitContext(reason);
        Signal signal = Signal.sell(stockCode, reason);
        RiskResult result = riskEngine.check(signal, positionManager.snapshotAccount());
        if (!result.isPass()) {
            log.warn("[Sim] 매도 거부: {} 사유={}", stockCode, result.getReason());
            return;
        }
        orderEngine.execute(signal);
        if (heldPosition(stockCode) == null) {
            trailingStopTracker.clear(stockCode); // 전량 청산 — 고점 추적 오염 방지
        }
    }

    private Position heldPosition(String stockCode) {
        return positionRepository.findByStockCode(stockCode)
                .filter(p -> p.getQuantity() > 0)
                .orElse(null);
    }
}
