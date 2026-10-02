package com.trading.dashboard;

import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 매도 체결가 추정 (2026-09-22 신설) — <b>원본은 손대지 않고 읽는 시점에만 계산한다.</b>
 *
 * <p>왜 추정이 필요한가: 모의 체결조회(VTTC8001R)가 매도에 빈 응답을 주는 결함
 * (CLAUDE.md 결함 5) 때문에 {@code order_history.filled_price}가 매도에서 0으로 남는다
 * (2026-09-21 운영 DB 실측: 체결 매도 104건 중 <b>101건이 0원</b>). 그래서 거래별 손익을
 * 정확히는 구할 수 없다.
 *
 * <p><b>폴백 3단계</b> — 위에서부터 성공하는 첫 단계를 쓴다:
 * <ol>
 *   <li>{@link Timeframe#MINUTE} — 그날 그 종목의 분봉 중 <b>기준 시각에 가장 가까운 봉</b>의 종가.
 *       paper에서 {@code MinuteCandleCollector}가 매일 축적하고 보존 60일이다.</li>
 *   <li>{@link Timeframe#DAILY} — 그날의 일봉 종가.
 *       ⚠ 운영 DB에는 현재 일봉이 한 건도 없다(일봉은 backtest-db에만 있다). 즉 이 단계는
 *       지금은 사실상 비어 있고, 나중에 운영 DB에 일봉이 들어올 때를 위한 길이다.</li>
 *   <li>없으면 <b>측정 불가</b> — 0원으로 꾸미지 않고 집계에서 뺀다.</li>
 * </ol>
 *
 * <p><b>KIS를 부르지 않는다</b> — DB에 있는 캔들만 읽는다(모의 유량 1건/초).
 */
@Component
public class SellPriceEstimator {

    public enum Source { MINUTE, DAILY, NONE }

    /** 추정 결과. {@code price}가 null이면 측정 불가다 */
    public record Estimate(Double price, Source source) {

        public static Estimate none() { return new Estimate(null, Source.NONE); }

        public boolean isMeasurable() { return price != null && price > 0; }
    }

    private final CandleHistoryRepository candleRepository;

    public SellPriceEstimator(CandleHistoryRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    /**
     * 한 번의 집계 동안 쓰는 조회기. 캔들 목록을 (종목·날짜·주기)별로 캐시한다 —
     * 매도 한 건마다 391행짜리 분봉을 다시 읽으면 화면 한 번에 수만 행을 훑게 된다.
     * <b>싱글턴 빈에 캐시를 두지 않는 이유</b>: 동시 요청끼리 상태를 나눠 갖지 않게,
     * 그리고 메모리가 무한정 늘지 않게 호출마다 새로 만들어 버린다.
     */
    public Lookup newLookup() {
        return new Lookup();
    }

    public final class Lookup {

        private final Map<String, List<CandleHistory>> cache = new HashMap<>();

        private Lookup() {}

        /** @param referenceAt 매도가 일어난 것으로 보는 시각 (TradePairer.fillTimeOf 참고) */
        public Estimate estimate(String stockCode, LocalDateTime referenceAt) {
            LocalDate date = referenceAt.toLocalDate();

            Estimate minute = nearestMinuteClose(stockCode, date, referenceAt);
            if (minute.isMeasurable()) return minute;

            return dailyClose(stockCode, date);
        }

        private Estimate nearestMinuteClose(String stockCode, LocalDate date, LocalDateTime at) {
            return candlesOf(stockCode, date, Timeframe.MINUTE).stream()
                    .min(Comparator.comparing(c -> gapTo(c, at)))
                    .map(c -> new Estimate(c.getClose(), Source.MINUTE))
                    .orElseGet(Estimate::none);
        }

        private Estimate dailyClose(String stockCode, LocalDate date) {
            return candlesOf(stockCode, date, Timeframe.DAILY).stream()
                    .findFirst()
                    .map(c -> new Estimate(c.getClose(), Source.DAILY))
                    .orElseGet(Estimate::none);
        }

        private List<CandleHistory> candlesOf(String stockCode, LocalDate date, Timeframe timeframe) {
            return cache.computeIfAbsent(stockCode + "|" + date + "|" + timeframe, k ->
                    candleRepository
                            .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                                    stockCode, timeframe, date, date));
        }
    }

    private static Duration gapTo(CandleHistory candle, LocalDateTime at) {
        return Duration.between(candle.getCandleDate().atTime(candle.getCandleTime()), at).abs();
    }
}
