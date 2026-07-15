package com.trading.settings;

import com.trading.risk.RiskLimits;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 설정 UI로 조정 가능한 투자 파라미터 카탈로그 — 기본값·허용 범위(min/max)의 단일 출처.
 *
 * 기본값은 코드 상수(RiskLimits, 각 Properties 클래스 초기값)와 동일하게 선언한다.
 * "기본값 원복"은 이 enum의 defaultValue로 되돌리는 것이다.
 * 범위 밖 값은 클램프하지 않고 저장 자체를 거부한다 (안전장치 명시 원칙).
 */
public enum ParamCatalog {

    // ── 리스크 한도 ──────────────────────────────────────────────────────────
    DAILY_LOSS_BLOCK("risk.dailyLossBlock", "리스크 한도", "일일손실 매수차단",
            ParamType.NUMBER, true,
            String.valueOf(RiskLimits.DAILY_LOSS_BLOCK), "-0.10", "-0.005",
            (b, v) -> b.riskLimits().setDailyLossBlock(Double.parseDouble(v)),
            b -> String.valueOf(b.riskLimits().getDailyLossBlock())),

    DAILY_LOSS_LIQUIDATE("risk.dailyLossLiquidate", "리스크 한도", "일일손실 강제청산",
            ParamType.NUMBER, true,
            String.valueOf(RiskLimits.DAILY_LOSS_LIQUIDATE), "-0.20", "-0.01",
            (b, v) -> b.riskLimits().setDailyLossLiquidate(Double.parseDouble(v)),
            b -> String.valueOf(b.riskLimits().getDailyLossLiquidate())),

    MDD_LIMIT("risk.mddLimit", "리스크 한도", "MDD 강제청산 한도",
            ParamType.NUMBER, true,
            String.valueOf(RiskLimits.MDD_LIMIT), "0.03", "0.30",
            (b, v) -> b.riskLimits().setMddLimit(Double.parseDouble(v)),
            b -> String.valueOf(b.riskLimits().getMddLimit())),

    MAX_POSITION_WEIGHT("risk.maxPositionWeight", "리스크 한도", "종목당 최대 비중",
            ParamType.NUMBER, true,
            String.valueOf(RiskLimits.MAX_POSITION_WEIGHT), "0.02", "0.30",
            (b, v) -> b.riskLimits().setMaxPositionWeight(Double.parseDouble(v)),
            b -> String.valueOf(b.riskLimits().getMaxPositionWeight())),

    MAX_POSITION_COUNT("risk.maxPositionCount", "리스크 한도", "최대 보유 종목 수",
            ParamType.INT, false,
            String.valueOf(RiskLimits.MAX_POSITION_COUNT), "1", "20",
            (b, v) -> b.riskLimits().setMaxPositionCount(Integer.parseInt(v)),
            b -> String.valueOf(b.riskLimits().getMaxPositionCount())),

    // ── 사이징 / 손절 ────────────────────────────────────────────────────────
    RISK_FRACTION_PER_TRADE("risk.riskFractionPerTrade", "사이징·손절", "1회 허용손실 (1R)",
            ParamType.NUMBER, true,
            String.valueOf(RiskLimits.RISK_FRACTION_PER_TRADE), "0.001", "0.03",
            (b, v) -> b.riskLimits().setRiskFractionPerTrade(Double.parseDouble(v)),
            b -> String.valueOf(b.riskLimits().getRiskFractionPerTrade())),

    ATR_STOP_MULTIPLIER("risk.atrStopMultiplier", "사이징·손절", "ATR 손절 배수",
            ParamType.NUMBER, false,
            String.valueOf(RiskLimits.ATR_STOP_MULTIPLIER), "1.0", "3.0",
            (b, v) -> b.riskLimits().setAtrStopMultiplier(Double.parseDouble(v)),
            b -> String.valueOf(b.riskLimits().getAtrStopMultiplier())),

    SIZING_MAX_DISTORTION("risk.sizingMaxDistortion", "사이징·손절", "사이징 왜곡 한도",
            ParamType.NUMBER, true,
            String.valueOf(RiskLimits.SIZING_MAX_DISTORTION), "0.05", "0.50",
            (b, v) -> b.riskLimits().setSizingMaxDistortion(Double.parseDouble(v)),
            b -> String.valueOf(b.riskLimits().getSizingMaxDistortion())),

    // ── 전략 ─────────────────────────────────────────────────────────────────
    STRATEGY_K("strategy.k", "전략", "돌파 계수 K",
            ParamType.NUMBER, false,
            "0.5", "0.3", "0.7",
            (b, v) -> b.strategy().setK(Double.parseDouble(v)),
            b -> String.valueOf(b.strategy().getK())),

    // ── 필터 ─────────────────────────────────────────────────────────────────
    ENTRY_WINDOW_ENABLED("filters.entryWindow.enabled", "필터", "진입 시간창 사용",
            ParamType.BOOL, false, "false", null, null,
            (b, v) -> b.filters().getEntryWindow().setEnabled(Boolean.parseBoolean(v)),
            b -> String.valueOf(b.filters().getEntryWindow().isEnabled())),

    ENTRY_WINDOW_NOT_BEFORE("filters.entryWindow.notBefore", "필터", "진입 허용 시각 (이후)",
            ParamType.TIME, false, "09:15", "09:05", "10:00",
            (b, v) -> b.filters().getEntryWindow().setNotBefore(LocalTime.parse(v)),
            b -> b.filters().getEntryWindow().getNotBefore().toString()),

    VOLUME_CONFIRM_ENABLED("filters.volumeConfirm.enabled", "필터", "거래량 확인 사용",
            ParamType.BOOL, false, "false", null, null,
            (b, v) -> b.filters().getVolumeConfirm().setEnabled(Boolean.parseBoolean(v)),
            b -> String.valueOf(b.filters().getVolumeConfirm().isEnabled())),

    VOLUME_CONFIRM_RATIO("filters.volumeConfirm.ratio", "필터", "거래량 배수 (전일 대비)",
            ParamType.NUMBER, false, "1.5", "1.0", "3.0",
            (b, v) -> b.filters().getVolumeConfirm().setRatio(Double.parseDouble(v)),
            b -> String.valueOf(b.filters().getVolumeConfirm().getRatio())),

    TRAILING_STOP_ENABLED("filters.trailingStop.enabled", "필터", "트레일링 스탑 사용",
            ParamType.BOOL, false, "false", null, null,
            (b, v) -> b.filters().getTrailingStop().setEnabled(Boolean.parseBoolean(v)),
            b -> String.valueOf(b.filters().getTrailingStop().isEnabled())),

    TRAILING_STOP_ARM("filters.trailingStop.armProfitPct", "필터", "트레일링 장착 수익률",
            ParamType.NUMBER, true, "0.03", "0.01", "0.10",
            (b, v) -> b.filters().getTrailingStop().setArmProfitPct(Double.parseDouble(v)),
            b -> String.valueOf(b.filters().getTrailingStop().getArmProfitPct())),

    TRAILING_STOP_TRAIL("filters.trailingStop.trailPct", "필터", "트레일링 하락폭",
            ParamType.NUMBER, true, "0.02", "0.005", "0.05",
            (b, v) -> b.filters().getTrailingStop().setTrailPct(Double.parseDouble(v)),
            b -> String.valueOf(b.filters().getTrailingStop().getTrailPct())),

    INDEX_REGIME_ENABLED("filters.indexRegime.enabled", "필터", "지수 레짐 필터 사용",
            ParamType.BOOL, false, "false", null, null,
            (b, v) -> b.filters().getIndexRegime().setEnabled(Boolean.parseBoolean(v)),
            b -> String.valueOf(b.filters().getIndexRegime().isEnabled())),

    DISCLOSURE_COOLDOWN_ENABLED("filters.disclosureCooldown.enabled", "필터", "공시 쿨다운 사용",
            ParamType.BOOL, false, "false", null, null,
            (b, v) -> b.filters().getDisclosureCooldown().setEnabled(Boolean.parseBoolean(v)),
            b -> String.valueOf(b.filters().getDisclosureCooldown().isEnabled())),

    DISCLOSURE_COOLDOWN_DAYS("filters.disclosureCooldown.cooldownDays", "필터", "공시 쿨다운 일수",
            ParamType.INT, false, "5", "1", "20",
            (b, v) -> b.filters().getDisclosureCooldown().setCooldownDays(Integer.parseInt(v)),
            b -> String.valueOf(b.filters().getDisclosureCooldown().getCooldownDays()));

    public enum ParamType { NUMBER, INT, BOOL, TIME }

    private final String key;
    private final String group;
    private final String label;
    private final ParamType type;
    private final boolean percent;   // UI에서 % 단위로 표시/입력 (저장은 소수)
    private final String defaultValue;
    private final String min;        // null이면 범위 검증 없음 (BOOL)
    private final String max;
    private final BiConsumer<ParamBeans, String> writer;
    private final Function<ParamBeans, String> reader;

    ParamCatalog(String key, String group, String label, ParamType type, boolean percent,
                 String defaultValue, String min, String max,
                 BiConsumer<ParamBeans, String> writer, Function<ParamBeans, String> reader) {
        this.key = key;
        this.group = group;
        this.label = label;
        this.type = type;
        this.percent = percent;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.writer = writer;
        this.reader = reader;
    }

    public String key() { return key; }
    public String group() { return group; }
    public String label() { return label; }
    public ParamType type() { return type; }
    public boolean percent() { return percent; }
    public String defaultValue() { return defaultValue; }
    public String min() { return min; }
    public String max() { return max; }

    public void apply(ParamBeans beans, String value) { writer.accept(beans, value); }
    public String read(ParamBeans beans) { return reader.apply(beans); }

    /** 검증 실패 시 사용자 메시지 반환, 통과 시 null */
    public String validate(String value) {
        if (value == null || value.isBlank()) return "값이 비어 있습니다";
        try {
            switch (type) {
                case NUMBER -> {
                    double v = Double.parseDouble(value);
                    if (min != null && v < Double.parseDouble(min)) return rangeMessage();
                    if (max != null && v > Double.parseDouble(max)) return rangeMessage();
                }
                case INT -> {
                    int v = Integer.parseInt(value);
                    if (min != null && v < Integer.parseInt(min)) return rangeMessage();
                    if (max != null && v > Integer.parseInt(max)) return rangeMessage();
                }
                case BOOL -> {
                    if (!"true".equals(value) && !"false".equals(value)) return "true/false만 허용됩니다";
                }
                case TIME -> {
                    LocalTime v = LocalTime.parse(value);
                    if (min != null && v.isBefore(LocalTime.parse(min))) return rangeMessage();
                    if (max != null && v.isAfter(LocalTime.parse(max))) return rangeMessage();
                }
            }
            return null;
        } catch (NumberFormatException | DateTimeParseException e) {
            return "형식이 올바르지 않습니다 (" + type + ")";
        }
    }

    private String rangeMessage() {
        if (percent) {
            return String.format("허용 범위 %s%% ~ %s%%",
                    trimPct(min), trimPct(max));
        }
        return "허용 범위 " + min + " ~ " + max;
    }

    private static String trimPct(String decimal) {
        double v = Double.parseDouble(decimal) * 100;
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public static ParamCatalog byKey(String key) {
        for (ParamCatalog p : values()) {
            if (p.key.equals(key)) return p;
        }
        return null;
    }
}
