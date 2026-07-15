package com.trading.settings;

import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 투자 파라미터 설정 API (SettingsController의 API 키 저장과 분리).
 *
 * GET  /api/params        — 그룹별 파라미터 목록 (현재값·기본값·min/max)
 * PUT  /api/params        — 부분 저장 {key: value}. 범위 밖 값은 거부 + 에러 반환
 * POST /api/params/reset  — 전체 기본값 원복
 */
@RestController
@RequestMapping("/api/params")
public class TradingParamController {

    private final TradingParamService paramService;

    public TradingParamController(TradingParamService paramService) {
        this.paramService = paramService;
    }

    @GetMapping
    public Map<String, Object> getParams() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", paramService.getAll());
        return result;
    }

    @PutMapping
    public Map<String, Object> updateParams(@RequestBody Map<String, String> body) {
        TradingParamService.UpdateResult r = paramService.update(body);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved",  r.saved());
        result.put("errors", r.errors());
        return result;
    }

    @PostMapping("/reset")
    public Map<String, Object> resetParams() {
        paramService.resetAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reset", true);
        result.put("groups", paramService.getAll());
        return result;
    }
}
