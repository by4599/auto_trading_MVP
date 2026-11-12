package com.trading.backtest;

import com.trading.bucket.BucketParameterResolver;
import com.trading.bucket.BucketParameters;
import com.trading.bucket.StrategyBucket;
import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultTracker;
import com.trading.risk.RiskLimitsProperties;
import com.trading.risk.StopLossArmer;
import com.trading.strategy.FilterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 백테스트 매수 후 ATR 손절선이 실제로 붙는가 — 실제 스프링 트랜잭션 프록시로 검증한다(2026-11-13 결함 수정의 회귀 테스트).
 *
 * <p>결함: {@code OrderEngine}이 부르는 {@code KisOrderClient.buy(code, qty, bucket)}(인터페이스 default)가
 * {@code this.buy(code, qty)}로 자기 호출해 @Transactional 프록시를 건너뛰었다 → 트랜잭션 없이 발행된
 * {@code OrderFilledEvent}를 {@code StopLossArmer}(AFTER_COMMIT)가 버려 stopPrice가 항상 null이었다.
 * 프록시를 거치지 않는 단위 테스트로는 재현되지 않으므로 JPA 슬라이스로 띄운다(테스트 트랜잭션은 끈다).
 */
@DataJpaTest
@ActiveProfiles("backtest")
@Import({BacktestOrderClient.class, BacktestPositionManager.class, BacktestMarketDataService.class,
        TradeRecorder.class, TradeResultTracker.class, BacktestCostProperties.class, BacktestClockConfig.class,
        StopLossArmer.class, AtrCalculator.class, BucketParameterResolver.class, RiskLimitsProperties.class,
        BacktestOrderClientStopArmingTest.Props.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("BacktestOrderClient — 칸 이름표 매수(3인자) 후 손절선 장착")
class BacktestOrderClientStopArmingTest {

    @TestConfiguration
    @EnableConfigurationProperties({BacktestDataProperties.class, BucketParameters.class, FilterProperties.class})
    static class Props {
    }

    private static final String CODE = "005930";
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Autowired com.trading.order.KisOrderClient orderClient;   // 프록시 — OrderEngine이 받는 것과 같은 빈
    @Autowired BacktestMarketDataService market;
    @Autowired CandleHistoryRepository candleRepository;
    @Autowired PositionRepository positionRepository;

    @Test
    @DisplayName("3인자 buy(OrderEngine 경로) 뒤 stopPrice = 체결가 − ATR(20) × 1.5")
    void bucketBuy_armsAtrStop() {
        givenWarmupSeries();
        market.setSimPrice(CODE, 110);

        orderClient.buy(CODE, 10, StrategyBucket.TREND);

        Position pos = positionRepository.findByStockCode(CODE).orElseThrow();
        assertThat(pos.getQuantity()).isEqualTo(10);
        assertThat(pos.getStopPrice()).as("손절선 장착 여부").isNotNull();
        assertThat(pos.getStopPrice()).isCloseTo(110 * 1.001 - 30, within(1e-6));
    }

    /** 워밍업 17일: o=100 h=110 l=90 c=100 → TR 20 → ATR 20 */
    private void givenWarmupSeries() {
        List<CandleHistory> rows = new ArrayList<>();
        for (int i = 25; i > 8; i--) {
            rows.add(CandleHistory.ofDaily(CODE, new Candle(TODAY.minusDays(i), 100, 110, 90, 100, 1000)));
        }
        candleRepository.saveAll(rows);
        market.loadSeries(List.of(CODE), TODAY.minusDays(30), TODAY);
        market.setSimDate(TODAY);
    }
}
