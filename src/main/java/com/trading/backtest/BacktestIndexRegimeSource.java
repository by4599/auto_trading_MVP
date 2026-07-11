package com.trading.backtest;

import com.trading.market.Candle;
import com.trading.risk.IndexRegimeSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 백테스트 지수 레짐 데이터원 — candle_history의 KOSPI 일봉으로
 * "당일 시가 < 전일 종가"(갭다운)를 판정한다. 시가는 개장 직후 확정되는
 * 정보이므로 일봉 재생에서도 선견 편향이 없다.
 */
@Component
@Profile("backtest")
public class BacktestIndexRegimeSource implements IndexRegimeSource {

    private final BacktestMarketDataService market;
    private final BacktestDataProperties properties;
    private final Clock clock;

    public BacktestIndexRegimeSource(BacktestMarketDataService market,
                                     BacktestDataProperties properties,
                                     Clock clock) {
        this.market = market;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<Boolean> isBearishRegime() {
        LocalDate simDate = LocalDate.now(clock);
        String kospi = properties.getKospiStorageCode();
        Candle today = market.dayBar(kospi, simDate);
        Candle prev  = market.previousCandle(kospi, simDate);
        if (today == null || prev == null) return Optional.empty();
        return Optional.of(today.getOpen() < prev.getClose());
    }
}
