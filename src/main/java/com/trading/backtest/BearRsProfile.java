package com.trading.backtest;

import java.util.ArrayList;
import java.util.List;

/**
 * 55_quant §0.4 격자 한 칸 — 지수 필터 사용 여부 + 하락장 상대강도 예외 설정.
 *
 * @param indexFilter true면 기존 지수 추세 필터(MA120, IndexTrendRule) 그대로
 * @param gate        null이면 예외 없음. 값이 있으면 지수 필터를 끄고 하락장 날만 이 조건으로 거른다
 */
record BearRsProfile(String code, String name, boolean indexFilter, BearRsConfig gate) {

    static BearRsProfile d0() {
        return new BearRsProfile("D0", "대조 — 지수 필터 그대로(하락장 날 매수 0)", true, null);
    }

    static BearRsProfile nof() {
        return new BearRsProfile("NOF", "대조 — 지수 필터 완전 제거(0.25R)", false, null);
    }

    static BearRsProfile of(BearRsConfig c) {
        String size = c.halfSize() ? "H" : "F";
        String code = String.format("R%d%s-%s", c.n(), c.conditionTag(), size);
        String rule = switch (c.condition()) {
            case A_POSITIVE -> "RS>0";
            case B_TOP -> String.format("RS 상위 %.0f%%", c.topFraction() * 100);
            case C_TOP_NEAR_HIGH -> String.format("RS 상위 %.0f%% + 종가≥%.2f×250일 고가", c.topFraction() * 100, c.highRatio());
        };
        String name = String.format("N=%d · %s · 하락장분 %s", c.n(), rule, c.halfSize() ? "0.125R" : "0.25R");
        return new BearRsProfile(code, name, false, c);
    }

    /** 사전 등록 14칸 (순서 고정) */
    static List<BearRsProfile> grid() {
        List<BearRsProfile> grid = new ArrayList<>();
        grid.add(d0());
        grid.add(nof());
        for (int n : List.of(60, 120)) {
            for (BearRsConfig.Condition cond : BearRsConfig.Condition.values()) {
                grid.add(of(BearRsConfig.of(n, cond, false)));
                grid.add(of(BearRsConfig.of(n, cond, true)));
            }
        }
        return grid;
    }

    /** 앵커 재현 전용 — D0 한 칸 */
    static List<BearRsProfile> anchor() {
        return List.of(d0());
    }
}
