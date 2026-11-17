package com.trading.backtest;

import com.trading.market.AtrCalculator;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 백테스트 손절 규칙 스위치 — 기본값은 수정된 엔진의 기본 동작 그대로다(2026-11-13).
 *
 * <p><b>배경</b>: 2026-07-19 ~ 2026-11-13 백테스트는 ATR 손절선이 장착되지 않았다({@code BacktestOrderClient} 3인자 buy 결함).
 * 수정 후에는 매수 직후 {@code StopLossArmer}가 라이브와 같은 손절선(체결가 − ATR × 배수)을 붙인다. 이 클래스는 측정용 손잡이 두 개만 둔다.
 * <ul>
 *   <li>{@link EntryDayStop} — 일봉은 진입 당일 저가가 돌파 전인지 후인지 모른다. SAME_DAY(기본, 비관: 진입일 저가로도 손절 판정) /
 *       FROM_NEXT_DAY(낙관: 진입일 손절 판정을 건너뛰고 다음 거래일부터)</li>
 *   <li>{@link #stopMultiplier} — NaN(기본)이면 사이징과 같은 배수로 붙은 손절선을 그대로 둔다. 값이 있으면 손절선만
 *       체결가 − ATR × 그 배수로 다시 놓는다(수량은 사이징 배수로 이미 정해짐 → 수량과 손절 폭의 효과 분리).
 *       0이면 "손절 없음" — 손절가 0원(가격이 닿지 않음, 옛 엔진과 같은 동작)</li>
 * </ul>
 */
@Component
@Profile("backtest")
public class BacktestStopRules {

    public enum EntryDayStop { SAME_DAY, FROM_NEXT_DAY }

    static final double NO_STOP = 0.0;

    private final PositionRepository positionRepository;
    private final BacktestMarketDataService market;
    private final AtrCalculator atrCalculator;

    private volatile EntryDayStop entryDayStop = EntryDayStop.SAME_DAY;
    private volatile double stopMultiplier = Double.NaN;
    /** 종목별 마지막 진입일 — 같은 날 손절 뒤 재진입한 포지션도 '진입일'로 알아보기 위해 */
    private final Map<String, LocalDate> lastEntryDate = new ConcurrentHashMap<>();

    public BacktestStopRules(PositionRepository positionRepository, BacktestMarketDataService market,
                             AtrCalculator atrCalculator) {
        this.positionRepository = positionRepository;
        this.market = market;
        this.atrCalculator = atrCalculator;
    }

    public EntryDayStop getEntryDayStop() { return entryDayStop; }
    public void setEntryDayStop(EntryDayStop v) { this.entryDayStop = v; }
    public double getStopMultiplier() { return stopMultiplier; }
    public void setStopMultiplier(double v) { this.stopMultiplier = v; }

    public void resetDefaults() {
        this.entryDayStop = EntryDayStop.SAME_DAY;
        this.stopMultiplier = Double.NaN;
        lastEntryDate.clear();
    }

    /**
     * 오늘 손절 판정(checkSameDayStop)을 할 것인가. 당일부터 규칙이면 항상 한다. 다음 날부터 규칙이면 <b>오늘 진입한</b>
     * 포지션만 건너뛴다 — 장 시작 때 보유분이 손절된 뒤 같은 날 다시 산 포지션도 오늘 진입분이다.
     */
    public boolean judgeSameDayStop(String stockCode, LocalDate date) {
        if (entryDayStop == EntryDayStop.SAME_DAY) return true;
        return !date.equals(lastEntryDate.get(stockCode));
    }

    /** 1차 매수 직후 호출 — 진입일을 기록하고, 손절 배수 오버라이드가 있으면 손절선만 다시 놓는다 */
    public void applyAfterEntry(String stockCode, LocalDate date) {
        Position pos = HeldPositions.of(positionRepository, stockCode);
        if (pos == null) return;
        lastEntryDate.put(stockCode, date);
        if (Double.isNaN(stopMultiplier)) return;
        if (stopMultiplier <= NO_STOP) {
            pos.armStopLoss(NO_STOP);
        } else {
            OptionalDouble atr = atrCalculator.atr(market.getDailyCandles(stockCode, AtrCalculator.PERIOD + 1));
            if (atr.isEmpty()) return; // 라이브 armer와 같이 ATR 없으면 손대지 않는다
            pos.armStopLoss(pos.getAveragePrice() - atr.getAsDouble() * stopMultiplier);
        }
        positionRepository.save(pos);
    }
}
