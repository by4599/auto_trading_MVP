package com.trading.backtest;

import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.order.OrderEngine;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.risk.MaxPositionCountRule;
import com.trading.risk.PendingOrderRule;
import com.trading.risk.PositionLimitRule;
import com.trading.risk.IndexRegimeSource;
import com.trading.risk.RiskResult;
import com.trading.risk.RiskRule;
import com.trading.signal.Signal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 금액 비중 진입 + 분할 매수 실행 — 백테스트 전용 (2026-11-12 사용자 요청 연구, {@link ScaleInProperties}).
 *
 * <p><b>1차 매수</b>: {@link DailyBarSimulator}가 RiskEngine 통과를 확인한 뒤 여기로 넘긴다. 수량만 R 사이징 대신
 * 금액 비중으로 정하고 {@code OrderEngine.executeManualBuy}(수량 지정 경로)로 낸다.
 *
 * <p><b>추가 매수</b>: 라이브 {@link PendingOrderRule}은 "보유 종목 매수"를 막으므로 추가 매수가 원천 불가다.
 * 측정을 위해 추가 매수는 리스크 룰 중 <b>보유 종목 추가 자체를 막는 3개</b>(중복 매수·보유 종목 수·종목 비중)만
 * 빼고 나머지 전부(낙폭 한도·일일 손실·연속 손실·장 마감·지수 추세 등)를 그대로 통과해야 한다.
 * 이 예외는 백테스트 측정용이다 — 라이브 적용은 룰 개정이 필요한 <b>사람 결정 사항</b>이다.
 *
 * <p>같은 날 손절선과 트리거를 둘 다 건드리면 손절이 먼저다(추가 매수 없음, 사용자 지정). 추가 매수 뒤에는
 * 기존 당일 진입 관례대로 그날 저가로 새 손절선을 비관적으로 판정한다({@code checkSameDayStop}이 이어서 수행).
 */
@Component
@Profile("backtest")
public class ScaleInExecutor {

    static final LocalTime ADD_ON_AT = LocalTime.of(10, 30);
    static final String ADD_ON_REASON = "ScaleIn-AddOn";

    static final String C_FIRST = "1차 매수";
    static final String C_FIRST_LIMITED = "1차 매수(하락추세 1/3 제한)";
    static final String C_FIRST_SKIPPED = "1차 매수 수량0 스킵";
    static final String C_ADD = "추가 매수 체결";
    static final String C_ADD_STOP_OVERLAP = "추가 매수 생략(같은 날 손절선 터치)";
    static final String C_ADD_INDEX = "추가 매수 생략(지수 하락추세)";
    static final String C_ADD_RISK = "추가 매수 거부(리스크 룰)";
    static final String C_ADD_CASH = "추가 매수 생략(현금 부족)";
    static final String C_NO_STOP = "사다리 미개설(1차 체결 직후 손절선 없음)";

    private static final List<Class<?>> ADD_ON_EXEMPT_RULES =
            List.of(PendingOrderRule.class, MaxPositionCountRule.class, PositionLimitRule.class);

    private final ScaleInProperties props;
    private final BacktestMarketDataService market;
    private final PositionRepository positionRepository;
    private final BacktestPositionManager positionManager;
    private final OrderEngine orderEngine;
    private final List<RiskRule> addOnRules;
    private final IndexRegimeSource indexSource;
    private final AtrCalculator atrCalculator;
    private final MutableClock clock;
    private final BacktestCostProperties costProperties;
    private final ScaleInRunStats stats;
    private final BacktestStopRules stopRules;

    private final Map<String, ScaleInMath.Ladder> ladders = new HashMap<>();

    public ScaleInExecutor(ScaleInProperties props, BacktestMarketDataService market,
                           PositionRepository positionRepository, BacktestPositionManager positionManager,
                           OrderEngine orderEngine, List<RiskRule> rules, IndexRegimeSource indexSource,
                           AtrCalculator atrCalculator, MutableClock clock,
                           BacktestCostProperties costProperties, ScaleInRunStats stats,
                           BacktestStopRules stopRules) {
        this.props = props;
        this.market = market;
        this.positionRepository = positionRepository;
        this.positionManager = positionManager;
        this.orderEngine = orderEngine;
        this.addOnRules = rules.stream()
                .filter(r -> ADD_ON_EXEMPT_RULES.stream().noneMatch(c -> c.isInstance(r)))
                .toList();
        this.indexSource = indexSource;
        this.atrCalculator = atrCalculator;
        this.clock = clock;
        this.costProperties = costProperties;
        this.stats = stats;
        this.stopRules = stopRules;
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** 1차 매수 — RiskEngine 통과 후, 시뮬가가 진입가로 세팅된 상태에서 호출된다 */
    public void enter(String stockCode, Signal buy, double entryRawPrice) {
        ladders.remove(stockCode);
        double equity = positionManager.snapshotAccount().getTotalAssetValue();
        boolean limited = props.getTranches() < 2 && firstTrancheOnlyGateBlocks();
        double amount = ScaleInMath.firstTrancheAmount(
                equity, props.getTargetWeight(), props.getTranches(), limited);
        int qty = ScaleInMath.quantity(amount, entryRawPrice,
                costProperties.getSlippageRate(), positionManager.getCash());
        if (qty < 1) {
            stats.count(C_FIRST_SKIPPED);
            return;
        }
        orderEngine.executeManualBuy(buy, qty);
        stopRules.applyAfterEntry(stockCode, LocalDate.now(clock));
        Position pos = held(stockCode);
        if (pos == null) return;
        stats.count(limited ? C_FIRST_LIMITED : C_FIRST);
        openLadder(stockCode, pos, equity);
    }

    private void openLadder(String stockCode, Position pos, double equity) {
        if (props.getTranches() < 2) return;
        if (pos.getStopPrice() == null) { // 손절선 없으면 사다리 없음 — 원인 진단용으로 센다
            stats.count(C_NO_STOP);
            return;
        }
        OptionalDouble atr = atrCalculator.atr(market.getDailyCandles(stockCode, AtrCalculator.PERIOD + 1));
        if (atr.isEmpty()) return;
        double stopDistance = pos.getAveragePrice() - pos.getStopPrice();
        double later = ScaleInMath.laterTrancheAmount(equity, props.getTargetWeight(), props.getTranches());
        ladders.put(stockCode, new ScaleInMath.Ladder(
                pos.getAveragePrice(), atr.getAsDouble(), stopDistance, later, 1));
    }

    /** 추가 매수 — 1차 체결 이후 고가가 트리거에 닿은 단계를 순서대로 채운다(하락 시 없음) */
    public void checkAddOns(String stockCode, LocalDate date, Candle bar) {
        Position pos = held(stockCode);
        if (pos == null) {
            ladders.remove(stockCode);
            return;
        }
        ScaleInMath.Ladder ladder = ladders.get(stockCode);
        if (ladder == null || ladder.filled() >= props.getTranches()) return;
        double nextTrigger = ScaleInMath.triggerPrice(
                ladder.firstFill(), ladder.atr(), props.getStepAtr(), ladder.filled() + 1);
        if (!ScaleInMath.triggered(bar.getHigh(), nextTrigger)) return;

        if (pos.getStopPrice() != null && bar.getLow() <= pos.getStopPrice()) {
            stats.count(C_ADD_STOP_OVERLAP); // 손절 먼저 — 뒤이은 checkSameDayStop이 청산한다
            return;
        }
        if (firstTrancheOnlyGateBlocks()) {
            stats.count(C_ADD_INDEX);
            return;
        }
        clock.setTo(date, ADD_ON_AT);
        ladders.put(stockCode, fillTriggered(stockCode, bar, ladder));
    }

    private ScaleInMath.Ladder fillTriggered(String stockCode, Candle bar, ScaleInMath.Ladder ladder) {
        ScaleInMath.Ladder current = ladder;
        for (int n = ladder.filled() + 1; n <= props.getTranches(); n++) {
            double trigger = ScaleInMath.triggerPrice(
                    ladder.firstFill(), ladder.atr(), props.getStepAtr(), n);
            if (!ScaleInMath.triggered(bar.getHigh(), trigger)) break;
            if (!buyAddOn(stockCode, current, ScaleInMath.addRawPrice(bar.getOpen(), trigger))) break;
            current = current.withFilled(n);
        }
        return current;
    }

    private boolean buyAddOn(String stockCode, ScaleInMath.Ladder ladder, double rawPrice) {
        market.setSimPrice(stockCode, rawPrice);
        Signal signal = Signal.buy(stockCode, ADD_ON_REASON);
        RiskResult risk = checkAddOnRules(signal);
        if (!risk.isPass()) {
            stats.count(C_ADD_RISK);
            return false;
        }
        int qty = ScaleInMath.quantity(ladder.trancheAmount(), rawPrice,
                costProperties.getSlippageRate(), positionManager.getCash());
        if (qty < 1) {
            stats.count(C_ADD_CASH);
            return false;
        }
        int before = held(stockCode).getQuantity();
        orderEngine.executeManualBuy(signal, qty);
        Position pos = held(stockCode);
        if (pos == null || pos.getQuantity() == before) return false;
        // StopLossArmer가 추가 체결가 기준으로 다시 장착한 손절선을 정의된 기준으로 덮어쓴다
        pos.armStopLoss(ScaleInMath.rebasedStop(props.getStopBasis(),
                ladder.firstFill(), pos.getAveragePrice(), ladder.stopDistance()));
        positionRepository.save(pos);
        stats.count(C_ADD);
        return true;
    }

    private RiskResult checkAddOnRules(Signal signal) {
        var account = positionManager.snapshotAccount();
        for (RiskRule rule : addOnRules) {
            RiskResult result = rule.validate(signal, account);
            if (!result.isPass()) return result;
        }
        return RiskResult.pass();
    }

    /** (c) 모드에서 지수가 하락 추세인가 — 판정 없음은 통과(IndexTrendRule과 같은 규약) */
    private boolean firstTrancheOnlyGateBlocks() {
        if (props.getIndexGate() != ScaleInProperties.IndexGate.FIRST_TRANCHE_ONLY) return false;
        return indexSource.isBelowTrend(props.getIndexMaPeriod()).orElse(false);
    }

    private Position held(String stockCode) {
        return HeldPositions.of(positionRepository, stockCode);
    }
}
