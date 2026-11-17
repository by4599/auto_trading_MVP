package com.trading.scheduler;

import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarProperties;
import com.trading.market.MarketCalendarService;
import com.trading.market.MarketDataService;
import com.trading.order.KisOrderClient;
import com.trading.order.OrderEngine;
import com.trading.order.OrderSizingService;
import com.trading.position.Account;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.OpportunityCostLogger;
import com.trading.risk.RiskEngine;
import com.trading.risk.RiskLimitsProperties;
import com.trading.risk.RiskResult;
import com.trading.risk.RiskRule;
import com.trading.risk.TradingStatusManager;
import com.trading.signal.Signal;
import com.trading.signal.SignalDispatcher;
import com.trading.strategy.Strategy;
import com.trading.universe.TradingUniverseItem;
import com.trading.universe.TradingUniverseRepository;
import com.trading.universe.TradingUniverseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 리스크가 매수를 막았을 때 그 사유가 실제로 기록되는가 (감사 24_audit M-2).
 *
 * <p>{@code TradingSchedulerTest}는 진입 게이트·라운드로빈만 보고 {@code isPass()} 분기를
 * 아예 안 본다. 그런데 2026-09-22에 추가된 것이 바로 그 <b>거부 분기</b>의 기록 호출이라
 * (그전까지는 TODO 주석만 있었고 호출부가 0개였다) 여기서 따로 고정한다.
 *
 * <p>별도 파일인 이유: 기존 파일에 얹으면 324줄로 300줄 상한을 넘는다.
 *
 * <p>Java 25 Mockito 제약: {@code OpportunityCostLogger}는 구체 클래스라 목으로 만들지 않고
 * 호출을 세는 실객체로 상속했다. 목은 인터페이스({@code MarketDataService}·
 * {@code KisOrderClient}·리포지토리)뿐이다.
 */
@DisplayName("TradingScheduler — 리스크 거부 사유 기록")
class TradingSchedulerDropRecordingTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate WEEKDAY = LocalDate.of(2026, 7, 15);   // 수요일, 휴장일 아님
    private static final String REJECT_REASON = "이미 보유 중인 종목: 005930";

    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final PositionManager positionManager = mock(PositionManager.class);
    private final TradingUniverseRepository universeRepository = mock(TradingUniverseRepository.class);
    private final KisOrderClient orderClient = mock(KisOrderClient.class);
    private final KisProperties kisProperties = new KisProperties();

    /** 항상 매수 신호를 내는 스텁 전략 */
    private static final Strategy BUY_STRATEGY = new Strategy() {
        @Override public String getName() { return "stub-buy"; }
        @Override public List<Signal> evaluate(String stockCode, List<Candle> candles) {
            return List.of(Signal.buy(stockCode, "stub-buy"));
        }
    };

    /** logDropped 호출을 세는 실객체 */
    private static final class RecordingLogger extends OpportunityCostLogger {
        private final List<String> calls = new ArrayList<>();
        private final boolean explode;

        RecordingLogger(boolean explode) { this.explode = explode; }

        @Override
        public void logDropped(Signal signal, String reason) {
            calls.add(signal.getStockCode() + "|" + reason);
            if (explode) throw new IllegalStateException("기록기 폭발");
        }
    }

    // ── 조립 ──────────────────────────────────────────────────────────────────

    private TradingScheduler schedulerWith(RiskRule rule, OpportunityCostLogger logger) {
        kisProperties.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        kisProperties.setAppkey("k");
        kisProperties.setSecretkey("s");
        kisProperties.setAccountNo("50000000-01");

        when(positionManager.snapshotAccount())
                .thenReturn(new Account(10_000_000, 0.0, 0, List.of()));
        when(marketDataService.getRecentCandles(anyString())).thenReturn(candles());
        // ATR이 서야 사이징이 성립한다 — 통과 케이스에서 실제 주문이 나가는지 보기 위해
        when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(candles());

        Clock fixed = Clock.fixed(
                LocalDateTime.of(WEEKDAY, LocalTime.NOON).atZone(KST).toInstant(), KST);

        return new TradingScheduler(marketDataService,
                new SignalDispatcher(List.of(BUY_STRATEGY)),
                new RiskEngine(List.of(rule)),
                new OrderEngine(orderClient, new TradingStatusManager(),
                        new OrderSizingService(marketDataService, positionManager,
                                new AtrCalculator(), new RiskLimitsProperties(),
                                com.trading.bucket.BucketTestSupport.disabledProps(),
                                com.trading.bucket.BucketTestSupport.disabledAccounts(),
                                com.trading.bucket.BucketTestSupport.defaultParams()),
                        mock(PositionRepository.class)),
                positionManager, new TradingStatusManager(), kisProperties,
                new TradingUniverseService(universeRepository),
                new MarketCalendarService(new MarketCalendarProperties(), fixed),
                logger);
    }

    /** ATR 산출에 필요한 최소 개수 — 진폭 20이라 ATR=20으로 떨어진다 */
    private static List<Candle> candles() {
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i <= AtrCalculator.PERIOD; i++) {
            bars.add(new Candle(LocalDate.of(2026, 7, 1).plusDays(i), 100, 110, 90, 100, 1000));
        }
        return bars;
    }

    private void givenUniverse(String... codes) {
        when(universeRepository.findAll()).thenReturn(
                Arrays.stream(codes).map(c -> TradingUniverseItem.of(c, c)).toList());
    }

    // ── 테스트 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("리스크가 거부하면 사유와 함께 1회 기록된다")
    void rejected_signal_is_recorded_once() {
        givenUniverse("005930");
        RecordingLogger logger = new RecordingLogger(false);

        schedulerWith((signal, account) -> RiskResult.reject(REJECT_REASON), logger).run();

        assertThat(logger.calls).containsExactly("005930|" + REJECT_REASON);
    }

    @Test
    @DisplayName("리스크가 통과하면 기록하지 않고 주문이 나간다")
    void passed_signal_is_not_recorded_and_the_order_goes_out() {
        givenUniverse("005930");
        RecordingLogger logger = new RecordingLogger(false);

        schedulerWith((signal, account) -> RiskResult.pass(), logger).run();

        assertThat(logger.calls).isEmpty();
        verify(orderClient).buy(eq("005930"), anyInt(), any());
    }

    @Test
    @DisplayName("기록이 터져도 루프는 멈추지 않고 다음 틱에 다음 종목으로 넘어간다")
    void a_failing_recorder_does_not_stop_the_loop() {
        givenUniverse("005930", "000660");
        RecordingLogger logger = new RecordingLogger(true);
        TradingScheduler scheduler =
                schedulerWith((signal, account) -> RiskResult.reject(REJECT_REASON), logger);

        assertThatCode(scheduler::run).doesNotThrowAnyException();   // tick 1 → 005930
        assertThatCode(scheduler::run).doesNotThrowAnyException();   // tick 2 → 000660

        assertThat(logger.calls).hasSize(2);
        verify(marketDataService).getRecentCandles("000660");        // 다음 종목까지 진행했다
        verify(orderClient, org.mockito.Mockito.never()).buy(anyString(), anyInt(), any());
    }
}
