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
import com.trading.strategy.ScalpingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 일봉 근사 청산 판정 (B-2, 계획 D6 — 애매하면 항상 불리하게).
 *
 * ① 09:05 이월분 갭다운 손절 (시가 ≤ 손절가 → 시가 체결)
 * ② 10:00 이월분 장중 손절 (저가 ≤ 손절가 → 손절가 체결)
 * ③ 당일 진입분 손절 — 저가 ≤ 손절가면 체결로 간주 (저가 발생 시점이 진입 전이었을
 *    가능성은 무시하는 비관적 관례)
 * ④ 스캘핑 목표 익절 · ⑤ 트레일링 스톱
 *
 * <p>청산은 모두 {@link #exitAt}의 단일 경로(Signal → RiskEngine → OrderEngine)를 탄다.
 * 하루 시퀀스의 <b>순서</b>는 {@link DailyBarSimulator}가 정한다.
 */
@Component
@Profile("backtest")
public class DailyBarExitSimulator {

    private static final Logger log = LoggerFactory.getLogger(DailyBarExitSimulator.class);

    static final String EXIT_STOP_GAP   = "StopLoss-GapOpen";
    static final String EXIT_STOP_INTRA = "StopLoss-ATR";

    private final BacktestMarketDataService market;
    private final RiskEngine riskEngine;
    private final OrderEngine orderEngine;
    private final PositionRepository positionRepository;
    private final PositionManager positionManager;
    private final BacktestOrderClient orderClient;
    private final TrailingStopTracker trailingStopTracker;
    private final MutableClock clock;
    private final ScalpingProperties scalpingProperties;

    public DailyBarExitSimulator(BacktestMarketDataService market,
                                 RiskEngine riskEngine,
                                 OrderEngine orderEngine,
                                 PositionRepository positionRepository,
                                 PositionManager positionManager,
                                 BacktestOrderClient orderClient,
                                 TrailingStopTracker trailingStopTracker,
                                 MutableClock clock,
                                 ScalpingProperties scalpingProperties) {
        this.market = market;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.positionRepository = positionRepository;
        this.positionManager = positionManager;
        this.orderClient = orderClient;
        this.trailingStopTracker = trailingStopTracker;
        this.clock = clock;
        this.scalpingProperties = scalpingProperties;
    }

    // ── ①② 이월 보유분 손절 ─────────────────────────────────────────────────

    void checkCarriedStop(String stockCode, LocalDate date, Candle bar) {
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

    // ── ③ 당일 진입분 손절 (비관적) ──────────────────────────────────────────

    void checkSameDayStop(String stockCode, LocalDate date, Candle bar) {
        Position pos = heldPosition(stockCode);
        if (pos == null || pos.getStopPrice() == null) return;
        if (bar.getLow() > pos.getStopPrice()) return;

        clock.setTo(date, LocalTime.of(14, 0));
        exitAt(stockCode, pos.getStopPrice(), EXIT_STOP_INTRA);
    }

    // ── ④ 스캘핑(방식3) 목표 익절 (BACKTEST-DESIGN §13) ────────────────────
    //
    // 스캘핑 전략의 청산은 ATR 손절/타임컷 외에 takeProfitPct 목표 도달 시 즉시 익절이
    // 핵심이다(paper의 StopLossMonitor). 스캘핑 전략이 켜진 실행에서만 당일 고가가 진입가 ×
    // (1+takeProfitPct) 이상이면 그 가격에 청산한다. 손절(loss-side) 판정을 먼저 거친
    // 뒤에 검사해 "애매하면 항상 불리하게" 원칙을 지킨다.

    void checkTakeProfit(String stockCode, LocalDate date, Candle bar) {
        if (!scalpingProperties.isEnabled()) return;
        Position pos = heldPosition(stockCode);
        if (pos == null) return;

        double target = pos.getAveragePrice() * (1 + scalpingProperties.getTakeProfitPct());
        if (bar.getHigh() >= target) {
            clock.setTo(date, LocalTime.of(14, 15));
            exitAt(stockCode, target, "TakeProfit-Scalping");
        }
    }

    // ── ⑤ 트레일링 스톱 필터 (§3.3, 기본 OFF — A/B 변형에서만 켠다) ──────────
    //
    // 이월(다일 보유) 포지션은 트레일 손절선을 "쉬고 있는 손절 주문"으로 모델링한다
    // (BACKTEST-DESIGN §14, checkCarriedStop과 동일 패턴 — 애매하면 항상 불리하게):
    //   레벨 = 어제까지의 고점 × (1−trail)  [오늘 고가 반영 전 — 선견 차단]
    //   ① 시가 ≤ 레벨 → 갭 관통, 시가 체결(더 나쁜 값)   ② 저가 ≤ 레벨 → 장중 터치, 레벨 체결
    //   미청산 시에만 오늘 고가를 반영해 내일의 레벨을 상향한다.
    // 진입 당일 포지션은 돌파 후 당일 경로라 고가가 진입 이후에 성립하므로, 기존 근사
    // (고가 반영 후 종가 판정, 레벨 체결)를 유지한다.

    void checkTrailingStop(String stockCode, LocalDate date, Candle bar, boolean carriedAtOpen) {
        Position pos = heldPosition(stockCode);
        if (pos == null) return;

        if (carriedAtOpen) {
            checkCarriedTrailingStop(stockCode, date, bar, pos);
            return;
        }

        // 진입 당일 근사 (기존 유지)
        trailingStopTracker.updateHigh(stockCode, bar.getHigh());
        trailingStopTracker.exitPrice(stockCode, bar.getClose(), pos.getAveragePrice())
                .ifPresent(level -> {
                    clock.setTo(date, LocalTime.of(14, 30));
                    exitAt(stockCode, level, "TrailingStop");
                });
    }

    private void checkCarriedTrailingStop(String stockCode, LocalDate date, Candle bar, Position pos) {
        java.util.OptionalDouble levelOpt =
                trailingStopTracker.exitLevelFromPriorHigh(stockCode, pos.getAveragePrice());
        if (levelOpt.isPresent()) {
            double level = levelOpt.getAsDouble();
            if (bar.getOpen() <= level) {          // 갭 관통 — 시가 체결(보수)
                clock.setTo(date, LocalTime.of(9, 5));
                exitAt(stockCode, bar.getOpen(), "TrailingStop-Gap");
                return;
            } else if (bar.getLow() <= level) {    // 장중 터치 — 레벨 체결
                clock.setTo(date, LocalTime.of(14, 30));
                exitAt(stockCode, level, "TrailingStop");
                return;
            }
        }
        trailingStopTracker.updateHigh(stockCode, bar.getHigh()); // 미청산 — 내일 레벨 상향
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
        return HeldPositions.of(positionRepository, stockCode);
    }
}
