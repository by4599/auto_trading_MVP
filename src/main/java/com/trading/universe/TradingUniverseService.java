package com.trading.universe;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 매매 유니버스 관리.
 *
 * 상한 20종목 (방법론 §2.5 — 1초 루프의 API 호출 한도와 감시 품질의 상한).
 * 최초 기동 시 테이블이 비어 있으면 삼성전자(005930)를 시드해
 * 기존 Phase 1 동작(1종목)을 그대로 보존한다.
 */
@Service
public class TradingUniverseService {

    private static final Logger log = LoggerFactory.getLogger(TradingUniverseService.class);

    public static final int MAX_UNIVERSE_SIZE = 20;

    private static final String SEED_STOCK_CODE = "005930";
    private static final String SEED_STOCK_NAME = "삼성전자";

    private final TradingUniverseRepository repository;

    public TradingUniverseService(TradingUniverseRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void seedIfEmpty() {
        if (repository.count() == 0) {
            repository.save(TradingUniverseItem.of(SEED_STOCK_CODE, SEED_STOCK_NAME));
            log.info("[Universe] 초기 시드: {} ({}) — 기존 1종목 동작 보존", SEED_STOCK_NAME, SEED_STOCK_CODE);
        }
    }

    public List<TradingUniverseItem> getAll() {
        return repository.findAll();
    }

    /** TradingScheduler가 매 틱 순회할 종목 코드 목록 */
    public List<String> getActiveCodes() {
        return repository.findAll().stream()
                .map(TradingUniverseItem::getStockCode)
                .toList();
    }

    @Transactional
    public TradingUniverseItem add(String stockCode, String stockName) {
        if (stockCode == null || !stockCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("종목코드는 6자리 숫자여야 합니다: " + stockCode);
        }
        if (repository.existsByStockCode(stockCode)) {
            throw new IllegalArgumentException(stockCode + "은(는) 이미 매매 유니버스에 있습니다");
        }
        if (repository.count() >= MAX_UNIVERSE_SIZE) {
            throw new IllegalArgumentException(
                    "매매 유니버스 상한(" + MAX_UNIVERSE_SIZE + "종목) 도달 — 기존 종목을 먼저 제거하세요");
        }
        TradingUniverseItem saved = repository.save(TradingUniverseItem.of(stockCode, stockName));
        log.warn("[Universe] 매매 대상 편입: {} ({}) — 총 {}종목",
                saved.getStockName(), stockCode, repository.count());
        return saved;
    }

    @Transactional
    public void remove(String stockCode) {
        if (!repository.existsByStockCode(stockCode)) {
            throw new IllegalArgumentException("매매 유니버스에 없는 종목입니다: " + stockCode);
        }
        repository.deleteByStockCode(stockCode);
        log.warn("[Universe] 매매 대상 제외: {} — 총 {}종목", stockCode, repository.count());
    }
}
