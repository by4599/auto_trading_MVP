package com.trading.universe;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 매매 유니버스 관리 API — 편입/제외는 사람이 UI에서 직접 한다 (수동 게이트 G1).
 */
@RestController
@RequestMapping("/api/universe")
public class UniverseController {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final TradingUniverseService universeService;

    public UniverseController(TradingUniverseService universeService) {
        this.universeService = universeService;
    }

    @GetMapping
    public List<Map<String, Object>> getUniverse() {
        return universeService.getAll().stream().map(UniverseController::toRow).toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody AddRequest req) {
        try {
            return ResponseEntity.ok(toRow(universeService.add(req.stockCode(), req.stockName())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{stockCode}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String stockCode) {
        try {
            universeService.remove(stockCode);
            return ResponseEntity.ok(Map.of("deleted", stockCode));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> toRow(TradingUniverseItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id",        item.getId());
        row.put("stockCode", item.getStockCode());
        row.put("stockName", item.getStockName());
        row.put("addedAt",   item.getAddedAt().format(DT_FMT));
        return row;
    }

    private record AddRequest(String stockCode, String stockName) {}
}
