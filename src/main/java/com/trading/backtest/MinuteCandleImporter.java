package com.trading.backtest;

import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.MinuteCandle;
import com.trading.market.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운영(paper) DB → 백테스트 DB 분봉 이관 (2026-08-04 신설).
 *
 * 분봉 수집기는 운영 DB에 쓰고 백테스트 엔진은 backtest-db를 읽어, 축적된 분봉이
 * 검증에 닿지 못하는 구조적 공백이 있었다. 이 임포터가 그 다리다.
 *
 * 멱등성은 (종목, 일자) 단위다 — 수집기가 하루치를 한 트랜잭션으로 저장하므로
 * 그 날짜에 한 건이라도 있으면 완결로 보고 통째로 건너뛴다. 반복 실행이 안전하다.
 */
@Component
@Profile("backtest")
public class MinuteCandleImporter {

    private static final Logger log = LoggerFactory.getLogger(MinuteCandleImporter.class);

    private final MinuteCandleSource source;
    private final CandleHistoryRepository repository;

    public MinuteCandleImporter(MinuteCandleSource source, CandleHistoryRepository repository) {
        this.source = source;
        this.repository = repository;
    }

    public record ImportResult(int importedRows, int importedDays, int skippedDays) {
        public String summaryLine() {
            return String.format("분봉 이관: %d건 저장 (신규 %d일치, 기존재 %d일치 스킵)",
                    importedRows, importedDays, skippedDays);
        }
    }

    public ImportResult importAll() {
        Map<DayKey, List<MinuteCandle>> grouped = groupByDay(source.fetchAll());

        int importedRows = 0;
        int importedDays = 0;
        int skippedDays = 0;

        for (Map.Entry<DayKey, List<MinuteCandle>> entry : grouped.entrySet()) {
            DayKey key = entry.getKey();
            if (repository.countByStockCodeAndTimeframeAndCandleDate(
                    key.stockCode(), Timeframe.MINUTE, key.date()) > 0) {
                skippedDays++;
                continue;
            }
            List<CandleHistory> rows = entry.getValue().stream()
                    .map(c -> CandleHistory.ofMinute(key.stockCode(), c))
                    .toList();
            repository.saveAll(rows);
            importedRows += rows.size();
            importedDays++;
        }

        ImportResult result = new ImportResult(importedRows, importedDays, skippedDays);
        log.info("[MinuteImport] {}", result.summaryLine());
        if (grouped.isEmpty()) {
            log.warn("[MinuteImport] ⚠ 운영 DB에 분봉이 없다 — 수집기가 도는 거래일마다 쌓이며, "
                    + "KIS는 과거 분봉을 주지 않으므로 축적 외에 다른 확보 경로는 없다");
        }
        return result;
    }

    /** (종목, 일자)별로 접는다 — 멱등성 판정 단위이자 저장 단위 */
    private Map<DayKey, List<MinuteCandle>> groupByDay(List<MinuteCandleSource.Row> rows) {
        Map<DayKey, List<MinuteCandle>> grouped = new LinkedHashMap<>();
        for (MinuteCandleSource.Row row : rows) {
            grouped.computeIfAbsent(
                    new DayKey(row.stockCode(), row.candle().date()), k -> new ArrayList<>())
                    .add(row.candle());
        }
        return grouped;
    }

    private record DayKey(String stockCode, LocalDate date) {}
}
