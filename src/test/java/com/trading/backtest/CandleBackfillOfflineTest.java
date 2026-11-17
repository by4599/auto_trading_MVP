package com.trading.backtest;

import com.trading.market.CandleHistoryClient;
import com.trading.market.CandleHistoryRepository;
import com.trading.universe.TradingUniverseRepository;
import com.trading.universe.TradingUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 오프라인 실행 스위치 ({@code backtest.offline}, 기본 false) — 켜면 백필이 KIS를 한 번도 부르지 않는다.
 *
 * <p>배경(2026-10-13): 랩 실행은 기동할 때마다 백필 검사를 하고, 저장 꼬리가 7일 넘게 밀렸거나 늦게 상장한
 * 종목이 있으면 KIS 일봉 API를 부른다. 모의투자 앱이 같은 계좌 호출 한도(초당 1건)를 쓰고 있을 때 연구용
 * 재생이 그 한도를 나눠 쓰면 안 된다. 이미 저장된 캔들로만 재생하고, 부족하면 커버리지 관문이 알려 준다.
 */
@DisplayName("CandleBackfillService — 오프라인 스위치")
class CandleBackfillOfflineTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    private CandleHistoryClient candleClient;
    private BacktestDataProperties properties;
    private CandleBackfillService sut;

    @BeforeEach
    void setUp() {
        candleClient = mock(CandleHistoryClient.class);
        CandleHistoryRepository repository = mock(CandleHistoryRepository.class);
        // 저장 이력 없음 → 평소라면 전 기간을 KIS에서 받아야 하는 최악의 상황
        when(repository.findFirstByStockCodeAndTimeframeOrderByCandleDateAsc(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findFirstByStockCodeAndTimeframeOrderByCandleDateDesc(any(), any()))
                .thenReturn(Optional.empty());
        TradingUniverseService universe = new TradingUniverseService(mock(TradingUniverseRepository.class));
        properties = new BacktestDataProperties();
        Clock fixed = Clock.fixed(ZonedDateTime.of(TODAY.atTime(3, 0), KST).toInstant(), KST);
        sut = new CandleBackfillService(candleClient, repository, universe, properties, fixed);
    }

    @Test
    @DisplayName("기본값은 false — 기존 백필 동작 그대로")
    void offlineDefaultsToFalse() {
        assertThat(new BacktestDataProperties().isOffline()).isFalse();
    }

    @Test
    @DisplayName("오프라인이면 백필 전체·추가 표본 모두 KIS를 부르지 않고 0건을 돌려준다")
    void offlineNeverCallsKis() {
        properties.setOffline(true);

        assertThat(sut.backfillAll()).isZero();
        assertThat(sut.backfillExtra(List.of("005930", "000660"))).isZero();
        verifyNoInteractions(candleClient);
    }

    @Test
    @DisplayName("대조군: 스위치가 꺼져 있으면 같은 상황에서 KIS를 부른다 — 막는 것은 스위치다")
    void onlineStillFetches() {
        when(candleClient.fetchDailyCandles(any(), any(), any())).thenReturn(List.of());
        when(candleClient.fetchIndexDailyCandles(any(), any(), any())).thenReturn(List.of());

        sut.backfillExtra(List.of("005930"));

        verify(candleClient).fetchDailyCandles(any(), any(), any());
        verify(candleClient, never()).fetchIndexDailyCandles(any(), any(), any());
    }
}
