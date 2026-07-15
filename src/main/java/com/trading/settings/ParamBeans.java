package com.trading.settings;

import com.trading.risk.RiskLimitsProperties;
import com.trading.strategy.FilterProperties;
import com.trading.strategy.StrategyParameters;

/** ParamCatalog의 read/apply 람다가 접근하는 런타임 파라미터 빈 묶음 */
public record ParamBeans(RiskLimitsProperties riskLimits,
                         StrategyParameters strategy,
                         FilterProperties filters) {
}
