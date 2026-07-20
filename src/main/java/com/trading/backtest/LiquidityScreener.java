package com.trading.backtest;

import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 유동성 필터 (방법론 §2.2 예고 — B-3 유니버스 확장 재검증에서 신설) — 일평균
 * 거래대금이 기준 미달인 종목을 후보에서 제외한다.
 *
 * 백테스트 후보 선정 전용이다. {@code trading_universe}(라이브 유니버스, G1 게이트 —
 * 사람이 편입/제외)에는 연결하지 않는다.
 */
@Component
public class LiquidityScreener {

    private static final Logger log = LoggerFactory.getLogger(LiquidityScreener.class);
    private static final int LOOKBACK_TRADING_DAYS = 20;

    private final CandleHistoryRepository candleHistoryRepository;

    public LiquidityScreener(CandleHistoryRepository candleHistoryRepository) {
        this.candleHistoryRepository = candleHistoryRepository;
    }

    /** asOf 기준 최근 20거래일 평균(거래량×종가) ≥ minDailyValue인 종목만 남긴다. */
    public List<String> filter(List<String> symbols, LocalDate asOf, double minDailyValue) {
        List<String> kept = new ArrayList<>();
        for (String symbol : symbols) {
            double avgValue = averageDailyValue(symbol, asOf);
            if (avgValue >= minDailyValue) {
                kept.add(symbol);
            } else {
                log.info("[LiquidityScreener] 제외: {} (일평균 거래대금 {}원 < 기준 {}원)",
                        symbol, (long) avgValue, (long) minDailyValue);
            }
        }
        return kept;
    }

    private double averageDailyValue(String symbol, LocalDate asOf) {
        List<CandleHistory> candles = candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        symbol, Timeframe.DAILY, asOf.minusDays(60), asOf);
        if (candles.isEmpty()) return 0;
        List<CandleHistory> recent = candles.size() > LOOKBACK_TRADING_DAYS
                ? candles.subList(candles.size() - LOOKBACK_TRADING_DAYS, candles.size())
                : candles;
        return recent.stream().mapToDouble(c -> c.getVolume() * c.getClose()).average().orElse(0);
    }
}
