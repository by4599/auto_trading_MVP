package com.trading.order;

import com.trading.bucket.BucketAccountService;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.StrategyBucket;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResultRepository;
import com.trading.risk.RiskLimitsProperties;
import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R 기반 수량 역산 (방법론 §4.3).
 * ATR은 실계산(AtrCalculator) — 갭 없는 일정 레인지 캔들로 ATR = 레인지가 되게 구성한다.
 * 손절폭 = ATR × 1.5 (RiskLimits.ATR_STOP_MULTIPLIER).
 */
@DisplayName("OrderSizingService — 1R 수량 역산·단주 내림·왜곡 스킵·칸 현금 한도")
class OrderSizingServiceTest {

    private MarketDataService marketDataService;
    private PositionManager positionManager;
    private PositionRepository positionRepository;
    private TradeResultRepository tradeResultRepository;
    private OrderSizingService sut;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        positionManager = mock(PositionManager.class);
        positionRepository = mock(PositionRepository.class);
        tradeResultRepository = mock(TradeResultRepository.class);
        sut = newSut(bucketProps(false));
    }

    private static BucketProperties bucketProps(boolean enabled) {
        return new BucketProperties(enabled, "2026-07-20",
                10_000_000, 10_000_000, 10_000_000, 10_000_000, false, false, false);
    }

    private OrderSizingService newSut(BucketProperties props) {
        when(positionRepository.findAll()).thenReturn(List.of());
        when(tradeResultRepository.findAll()).thenReturn(List.of());
        return new OrderSizingService(marketDataService, positionManager, new AtrCalculator(),
                new RiskLimitsProperties(), props,
                new BucketAccountService(props, positionRepository, tradeResultRepository), com.trading.bucket.BucketTestSupport.defaultParams());
    }

    private void givenAtr(double atr) {
        List<Candle> candles = new ArrayList<>();
        double mid = 100_000;
        for (int i = 0; i < 15; i++) {
            candles.add(new Candle(LocalDate.now().minusDays(15 - i),
                    mid, mid + atr / 2, mid - atr / 2, mid, 1000));
        }
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(candles);
    }

    private void givenEquity(double equity) {
        when(positionManager.snapshotAccount())
                .thenReturn(new Account(equity, 0.0, 0, List.of()));
    }

    @Test
    @DisplayName("1R=10만, 손절폭=3천 → 33주 (내림), 왜곡 1%로 통과")
    void computes_floor_quantity() {
        givenAtr(2_000);              // 손절폭 = 2000 × 1.5 = 3,000
        givenEquity(10_000_000);      // 1R = 100,000

        OrderSizingService.SizingResult result = sut.sizeBuy("005930");

        assertThat(result.executable()).isTrue();
        assertThat(result.quantity()).isEqualTo(33);   // floor(100000/3000)=33
        assertThat(result.stopDistance()).isEqualTo(3_000.0);
    }

    @Test
    @DisplayName("1주 리스크가 1R 초과 (고가 종목) → 스킵")
    void skips_when_one_share_exceeds_one_r() {
        givenAtr(100_000);            // 손절폭 = 150,000 > 1R 100,000
        givenEquity(10_000_000);

        OrderSizingService.SizingResult result = sut.sizeBuy("005930");

        assertThat(result.executable()).isFalse();
        assertThat(result.skipReason()).contains("1R");
    }

    @Test
    @DisplayName("단주 내림 왜곡 40% > 한도 20% → 스킵")
    void skips_on_rounding_distortion() {
        givenAtr(40_000);             // 손절폭 = 60,000 → qty=1, 실제리스크 60,000 (1R 대비 -40%)
        givenEquity(10_000_000);

        OrderSizingService.SizingResult result = sut.sizeBuy("005930");

        assertThat(result.executable()).isFalse();
        assertThat(result.skipReason()).contains("왜곡");
    }

    @Test
    @DisplayName("일봉 부족으로 ATR 산출 불가 → 스킵")
    void skips_when_atr_unavailable() {
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(List.of());
        givenEquity(10_000_000);

        assertThat(sut.sizeBuy("005930").executable()).isFalse();
    }

    @Test
    @DisplayName("일봉 조회 예외 → 스킵 (예외 전파 없음)")
    void skips_on_market_data_failure() {
        when(marketDataService.getDailyCandles(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("KIS 장애"));
        givenEquity(10_000_000);

        assertThat(sut.sizeBuy("005930").executable()).isFalse();
    }

    @Test
    @DisplayName("총자산 조회 불가 (equity<=0) → 스킵")
    void skips_when_equity_unknown() {
        givenAtr(2_000);
        givenEquity(0);

        assertThat(sut.sizeBuy("005930").executable()).isFalse();
    }

    // ── 지갑 칸 실험 (trading.bucket.enabled=true) ───────────────────────────

    @Test
    @DisplayName("칸 ON: 계좌가 5천만이어도 1R은 칸 자산 1천만 기준 → 33주")
    void bucket_equity_replaces_account_equity() {
        sut = newSut(bucketProps(true));
        givenAtr(2_000);              // 손절폭 3,000
        givenEquity(50_000_000);      // 계좌 전체 — 칸 ON이면 무시되어야 한다

        OrderSizingService.SizingResult result = sut.sizeBuy("005930", StrategyBucket.VB);

        assertThat(result.executable()).isTrue();
        assertThat(result.quantity()).isEqualTo(33);   // 1R = 10,000,000×1% = 100,000
    }

    @Test
    @DisplayName("칸 ON: 가용 현금 300만·주가 10만 → 33주가 30주로 축소")
    void bucket_cash_caps_quantity() {
        Position held = Position.empty("000660");
        held.applyBuy(70, 100_000);   // 투입 원가 700만 → 가용 현금 300만
        when(positionRepository.findAll()).thenReturn(List.of(held));
        sut = new OrderSizingService(marketDataService, positionManager, new AtrCalculator(),
                new RiskLimitsProperties(), bucketProps(true),
                new BucketAccountService(bucketProps(true), positionRepository, tradeResultRepository), com.trading.bucket.BucketTestSupport.defaultParams());
        when(tradeResultRepository.findAll()).thenReturn(List.of());
        givenAtr(2_000);
        givenEquity(50_000_000);

        OrderSizingService.SizingResult result = sut.sizeBuy("005930", StrategyBucket.VB);

        assertThat(result.executable()).isTrue();
        assertThat(result.quantity()).isEqualTo(30);   // floor(3,000,000 / 100,000)
    }

    @Test
    @DisplayName("칸 ON: 가용 현금이 1주 값도 안 되면 → 스킵")
    void bucket_cash_exhausted_skips() {
        Position held = Position.empty("000660");
        held.applyBuy(100, 99_500);   // 투입 원가 995만 → 가용 현금 5만 < 주가 10만
        when(positionRepository.findAll()).thenReturn(List.of(held));
        sut = new OrderSizingService(marketDataService, positionManager, new AtrCalculator(),
                new RiskLimitsProperties(), bucketProps(true),
                new BucketAccountService(bucketProps(true), positionRepository, tradeResultRepository), com.trading.bucket.BucketTestSupport.defaultParams());
        when(tradeResultRepository.findAll()).thenReturn(List.of());
        givenAtr(2_000);
        givenEquity(50_000_000);

        OrderSizingService.SizingResult result = sut.sizeBuy("005930", StrategyBucket.VB);

        assertThat(result.executable()).isFalse();
        assertThat(result.skipReason()).contains("가용 현금");
    }
}
