package com.trading.settings;

import com.trading.risk.RiskLimitsProperties;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.StrategyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 투자 파라미터의 조회·검증·저장·런타임 반영 (설정 UI 백엔드).
 *
 * 반영 규칙:
 * - 시작 시 DB(app_setting)에 저장된 값만 런타임 빈에 덮어쓴다.
 *   DB에 없는 파라미터는 코드 기본값 또는 yml 로드값 그대로 유지.
 * - 저장 시 개별 범위 검증 → 교차 검증 → DB 저장 + 즉시 반영.
 *   범위 밖 값은 클램프하지 않고 거부한다.
 * - 원복은 DB 전체 삭제 + ParamCatalog 기본값(코드 상수) 적용.
 */
@Component
public class TradingParamService {

    private static final Logger log = LoggerFactory.getLogger(TradingParamService.class);

    private final AppSettingRepository repository;
    private final ParamBeans beans;

    public TradingParamService(AppSettingRepository repository,
                               RiskLimitsProperties riskLimits,
                               StrategyParameters strategyParameters,
                               FilterProperties filterProperties) {
        this.repository = repository;
        this.beans = new ParamBeans(riskLimits, strategyParameters, filterProperties);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        int applied = 0;
        for (AppSetting setting : repository.findAll()) {
            ParamCatalog param = ParamCatalog.byKey(setting.getParamKey());
            if (param == null) {
                log.warn("[Param] 알 수 없는 저장 키 무시: {}", setting.getParamKey());
                continue;
            }
            String error = param.validate(setting.getParamValue());
            if (error != null) {
                log.warn("[Param] 저장값 검증 실패 — 기본값 유지: {}={} ({})",
                        setting.getParamKey(), setting.getParamValue(), error);
                continue;
            }
            param.apply(beans, setting.getParamValue());
            applied++;
        }
        if (applied > 0) {
            log.info("[Param] 저장된 투자 파라미터 {}건 적용 완료", applied);
        }
    }

    /** 그룹 순서 유지 목록 — {group: [{key,label,type,percent,value,defaultValue,min,max}]} */
    public List<Map<String, Object>> getAll() {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (ParamCatalog p : ParamCatalog.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key",          p.key());
            item.put("label",        p.label());
            item.put("type",         p.type().name());
            item.put("percent",      p.percent());
            item.put("value",        p.read(beans));
            item.put("defaultValue", p.defaultValue());
            item.put("min",          p.min());
            item.put("max",          p.max());
            grouped.computeIfAbsent(p.group(), g -> new ArrayList<>()).add(item);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        grouped.forEach((name, params) -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", name);
            g.put("params", params);
            groups.add(g);
        });
        return groups;
    }

    @Transactional
    public UpdateResult update(Map<String, String> requested) {
        List<String> saved = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        Map<ParamCatalog, String> valid = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : requested.entrySet()) {
            ParamCatalog param = ParamCatalog.byKey(e.getKey());
            if (param == null) {
                errors.put(e.getKey(), "알 수 없는 파라미터");
                continue;
            }
            String error = param.validate(e.getValue());
            if (error != null) {
                errors.put(e.getKey(), error);
                continue;
            }
            valid.put(param, e.getValue());
        }

        crossValidate(valid, errors);

        for (Map.Entry<ParamCatalog, String> e : valid.entrySet()) {
            if (errors.containsKey(e.getKey().key())) continue;
            String key = e.getKey().key();
            String value = e.getValue();
            String before = e.getKey().read(beans);

            repository.findById(key).ifPresentOrElse(
                    existing -> { existing.updateValue(value); repository.save(existing); },
                    () -> repository.save(AppSetting.of(key, value)));
            e.getKey().apply(beans, value);
            saved.add(key);
            log.info("[Param] {} : {} → {}", key, before, value);
        }
        return new UpdateResult(saved, errors);
    }

    @Transactional
    public void resetAll() {
        repository.deleteAll();
        for (ParamCatalog p : ParamCatalog.values()) {
            p.apply(beans, p.defaultValue());
        }
        log.info("[Param] 전체 파라미터 기본값 원복 완료 ({}건)", ParamCatalog.values().length);
    }

    // ── 교차 검증 — 개별 범위는 통과했지만 조합이 모순인 경우 ────────────────

    private void crossValidate(Map<ParamCatalog, String> valid, Map<String, String> errors) {
        double block     = effective(valid, ParamCatalog.DAILY_LOSS_BLOCK);
        double liquidate = effective(valid, ParamCatalog.DAILY_LOSS_LIQUIDATE);
        if (liquidate >= block) {
            String msg = "강제청산 한도는 매수차단 한도보다 낮아야 합니다 (예: 차단 -3%, 청산 -5%)";
            rejectIfRequested(valid, errors, ParamCatalog.DAILY_LOSS_BLOCK, msg);
            rejectIfRequested(valid, errors, ParamCatalog.DAILY_LOSS_LIQUIDATE, msg);
        }

        double arm   = effective(valid, ParamCatalog.TRAILING_STOP_ARM);
        double trail = effective(valid, ParamCatalog.TRAILING_STOP_TRAIL);
        if (trail >= arm) {
            String msg = "트레일링 하락폭은 장착 수익률보다 작아야 합니다";
            rejectIfRequested(valid, errors, ParamCatalog.TRAILING_STOP_ARM, msg);
            rejectIfRequested(valid, errors, ParamCatalog.TRAILING_STOP_TRAIL, msg);
        }
    }

    /** 이번 요청에 포함됐으면 제안값, 아니면 현재 런타임 값 */
    private double effective(Map<ParamCatalog, String> valid, ParamCatalog param) {
        String value = valid.getOrDefault(param, param.read(beans));
        return Double.parseDouble(value);
    }

    private static void rejectIfRequested(Map<ParamCatalog, String> valid,
                                          Map<String, String> errors,
                                          ParamCatalog param, String message) {
        if (valid.containsKey(param)) errors.put(param.key(), message);
    }

    public record UpdateResult(List<String> saved, Map<String, String> errors) {}
}
