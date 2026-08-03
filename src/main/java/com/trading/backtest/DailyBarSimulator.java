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
import com.trading.strategy.RsiProperties;
import com.trading.strategy.ScalpingProperties;
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
    private final ScalpingProperties scalpingProperties;
    private final RsiProperties rsiProperties;

    public DailyBarSimulator(BacktestMarketDataService market,
                             SignalDispatcher signalDispatcher,
                             RiskEngine riskEngine,
                             OrderEngine orderEngine,
                             PositionRepository positionRepository,
                             PositionManager positionManager,
                             BacktestOrderClient orderClient,
                             TrailingStopTracker trailingStopTracker,
                             MutableClock clock,
                             ScalpingProperties scalpingProperties,
                             RsiProperties rsiProperties) {
        this.market = market;
        this.signalDispatcher = signalDispatcher;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
        this.positionRepository = positionRepository;
        this.positionManager = positionManager;
        this.orderClient = orderClient;
        this.trailingStopTracker = trailingStopTracker;
        this.clock = clock;
        this.scalpingProperties = scalpingProperties;
        this.rsiProperties = rsiProperties;
    }

    public void simulateDay(String stockCode, LocalDate date) {
        Candle bar = market.dayBar(stockCode, date);
        if (bar == null) return; // 거래정지/데이터 공백 — 포지션 이월
        if (market.previousCandle(stockCode, date) == null) return; // 시리즈 첫날 — 전일 없음

        // 장 시작 시점(오늘 진입 전) 보유 여부 — 트레일 판정에서 이월/당일진입을 구분한다
        boolean carriedAtOpen = heldPosition(stockCode) != null;

        checkCarriedStop(stockCode, date, bar);

        if (rsiProperties.isEnabled()) {
            // 평균회귀(RSI2, 전략3) 종가 진입 시퀀스 — 이월분 트레일 청산 먼저, 그다음 종가 진입.
            // 당일 진입분은 종가에 사므로 당일 손절/트레일(장중 저가·고가는 진입 전 성립)을 판정하지
            // 않는다: checkSameDayStop·checkTakeProfit은 호출하지 않고, checkTrailingStop은 진입 전
            // 호출이라 당일 진입분(보유 없음)에는 no-op이다(이월분만 처리).
            checkTrailingStop(stockCode, date, bar, carriedAtOpen);
            checkMeanReversionEntry(stockCode, date, bar);
            return;
        }

        checkEntry(stockCode, date, bar);
        checkSameDayStop(stockCode, date, bar);
        checkTakeProfit(stockCode, date, bar);
        checkTrailingStop(stockCode, date, bar, carriedAtOpen);
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

    // ── ③′ 평균회귀 종가 진입 (RSI2, 전략3=스캘핑 대체 — 2026-08) ─────────────────
    //
    // RSI(2) 과매도는 장중 고가에서는 성립하지 않고 종가에 확정되는 신호라, 돌파용 고가-발화·
    // 이분탐색(resolveEntryPrice) 경로로는 잡히지 않는다. 그래서 시뮬 현재가를 당일 종가로 놓고
    // 전략을 평가해, 발화하면 종가에 진입한다(Connors 관례 — 신호 확정 시점이 종가). 출구는
    // 프레임워크(ATR 손절·다일 트레일링·최대보유)가 맡는다. rsiProperties.enabled일 때만 호출된다.

    private void checkMeanReversionEntry(String stockCode, LocalDate date, Candle bar) {
        if (heldPosition(stockCode) != null) return; // 이미 보유 — 중복 진입 없음(라이브 PendingOrderRule 대응)
        clock.setTo(date, LocalTime.of(15, 0));

        market.setSimPrice(stockCode, bar.getClose());
        List<Signal> signals = signalDispatcher.dispatch(
                stockCode, market.getRecentCandles(stockCode));
        Signal buy = signals.stream().filter(Signal::isBuy).findFirst().orElse(null);
        if (buy == null) return;

        RiskResult result = riskEngine.check(buy, positionManager.snapshotAccount());
        if (!result.isPass()) {
            log.debug("[Sim] {} {} 평균회귀 매수 거부: {}", date, stockCode, result.getReason());
            return;
        }
        orderEngine.execute(buy);
        if (heldPosition(stockCode) != null) {
            // 트레일링 baseline = 진입 종가 (당일 고가는 진입 전에 성립하므로 쓰지 않는다)
            trailingStopTracker.updateHigh(stockCode, bar.getClose());
        }
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

    // ── ④-b 스캘핑(방식3) 목표 익절 (BACKTEST-DESIGN §13) ────────────────────
    //
    // 스캘핑 전략의 청산은 ATR 손절/타임컷 외에 takeProfitPct 목표 도달 시 즉시 익절이
    // 핵심이다(paper의 StopLossMonitor). 이 시뮬레이터에는 대응 로직이 없었으므로,
    // 스캘핑 전략이 켜진 실행에서만(ScalpingProperties.enabled) 당일 고가가 진입가 ×
    // (1+takeProfitPct) 이상이면 그 가격에 청산한다. 손절(loss-side) 판정을 먼저 거친
    // 뒤에 검사해 "애매하면 항상 불리하게" 원칙을 지킨다.

    private void checkTakeProfit(String stockCode, LocalDate date, Candle bar) {
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

    private void checkTrailingStop(String stockCode, LocalDate date, Candle bar, boolean carriedAtOpen) {
        Position pos = heldPosition(stockCode);
        if (pos == null) return;

        if (carriedAtOpen) {
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
