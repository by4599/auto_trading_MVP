package com.trading.backtest;

import com.trading.strategy.AccumulationProperties;
import com.trading.strategy.AccumulationRule;
import com.trading.strategy.DonchianProperties;
import com.trading.strategy.MaBreakoutProperties;
import com.trading.strategy.RsiProperties;
import com.trading.strategy.ScalpingProperties;
import com.trading.strategy.StrategyParameters;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 진입 전략 on/off 스위치 묶음 (백테스트 전용).
 *
 * <p>랩마다 "이 전략만 켜고 나머지는 끈다"를 직접 세팅하던 코드를 한곳에 모은 것이다.
 * <b>어떤 스위치를 건드리는지가 랩마다 다르므로</b>(뒤에 추가된 돈치안·RSI를 함께 끄는
 * 조합과 손대지 않는 조합이 공존한다) 메서드를 하나로 합치지 않고 원본 호출과
 * 1:1로 남겼다 — 합치면 yml에서 켠 전략이 조용히 꺼지거나 켜진 채 남는다.
 */
@Component
@Profile("backtest")
public class StrategyToggles {

    private final StrategyParameters strategyParameters;
    private final MaBreakoutProperties maBreakoutProperties;
    private final ScalpingProperties scalpingProperties;
    private final DonchianProperties donchianProperties;
    private final RsiProperties rsiProperties;
    private final AccumulationProperties accumulationProperties;

    public StrategyToggles(StrategyParameters strategyParameters,
                           MaBreakoutProperties maBreakoutProperties,
                           ScalpingProperties scalpingProperties,
                           DonchianProperties donchianProperties,
                           RsiProperties rsiProperties,
                           AccumulationProperties accumulationProperties) {
        this.strategyParameters = strategyParameters;
        this.maBreakoutProperties = maBreakoutProperties;
        this.scalpingProperties = scalpingProperties;
        this.donchianProperties = donchianProperties;
        this.rsiProperties = rsiProperties;
        this.accumulationProperties = accumulationProperties;
    }

    /** VB(방식1)만 — MA돌파·스캘핑 OFF (돈치안·RSI는 건드리지 않음) */
    public void enableVbOnly() {
        strategyParameters.setEnabled(true);
        maBreakoutProperties.setEnabled(false);
        scalpingProperties.setEnabled(false);
    }

    /** VB만 — 5종 전부 명시 세팅 (B동 랩: 신규 진입 전략이 섞이지 않도록 전부 끈다) */
    public void enableVbOnlyResetAll() {
        strategyParameters.setEnabled(true);
        maBreakoutProperties.setEnabled(false);
        scalpingProperties.setEnabled(false);
        donchianProperties.setEnabled(false);
        rsiProperties.setEnabled(false);
    }

    /** MA 정배열 돌파(방식2)만 — VB·스캘핑 OFF (돈치안·RSI는 건드리지 않음) */
    public void enableMaBreakoutOnly() {
        strategyParameters.setEnabled(false);
        maBreakoutProperties.setEnabled(true);
        scalpingProperties.setEnabled(false);
    }

    /** MA 정배열 돌파만 — 5종 전부 명시 세팅 (regime-lab MA 재현) */
    public void enableMaBreakoutOnlyResetAll() {
        strategyParameters.setEnabled(false);
        maBreakoutProperties.setEnabled(true);
        scalpingProperties.setEnabled(false);
        donchianProperties.setEnabled(false);
        rsiProperties.setEnabled(false);
    }

    /** 눌림목 반등 스캘핑(방식3)만 — VB·MA돌파 OFF */
    public void enableScalpingOnly() {
        strategyParameters.setEnabled(false);
        maBreakoutProperties.setEnabled(false);
        scalpingProperties.setEnabled(true);
    }

    /** 돈치안 돌파만 — VB·MA돌파·스캘핑 OFF (RSI는 건드리지 않음) */
    public void enableDonchianOnly() {
        strategyParameters.setEnabled(false);
        maBreakoutProperties.setEnabled(false);
        scalpingProperties.setEnabled(false);
        donchianProperties.setEnabled(true);
    }

    /** 돈치안 돌파만 — RSI까지 명시 OFF */
    public void enableDonchianOnlyResetRsi() {
        strategyParameters.setEnabled(false);
        maBreakoutProperties.setEnabled(false);
        scalpingProperties.setEnabled(false);
        donchianProperties.setEnabled(true);
        rsiProperties.setEnabled(false);
    }

    /** RSI(2) 평균회귀만 — 나머지 4종 OFF */
    public void enableRsiOnly() {
        strategyParameters.setEnabled(false);
        maBreakoutProperties.setEnabled(false);
        scalpingProperties.setEnabled(false);
        donchianProperties.setEnabled(false);
        rsiProperties.setEnabled(true);
    }

    /**
     * 매집 흔적(흔적레이더 §18)만 — 나머지 5종 OFF, 규칙 지정. 켜면 시뮬레이터가 다음 날 시가 진입 경로를 탄다.
     * 다른 메서드들은 이 스위치를 건드리지 않으므로 랩이 끝날 때 {@link #accumulation()}으로 반드시 복원한다.
     */
    public void enableAccumulationOnly(AccumulationRule rule) {
        strategyParameters.setEnabled(false);
        maBreakoutProperties.setEnabled(false);
        scalpingProperties.setEnabled(false);
        donchianProperties.setEnabled(false);
        rsiProperties.setEnabled(false);
        accumulationProperties.setRule(rule);
        accumulationProperties.setEnabled(true);
    }

    /** 매집 흔적 스위치 (기준선 실행 전 OFF · 랩 종료 복원용) */
    public AccumulationProperties accumulation() {
        return accumulationProperties;
    }

    /** VB의 K (변동성 돌파 계수) — K 민감도 스윕 전용 */
    public void setK(double k) {
        strategyParameters.setK(k);
    }

    /** 스캘핑 자체 파라미터 (민감도 스윕 전용) */
    public ScalpingProperties scalping() {
        return scalpingProperties;
    }

    /** 돈치안 자체 파라미터 (민감도·추세 지도 스윕 전용) */
    public DonchianProperties donchian() {
        return donchianProperties;
    }
}
