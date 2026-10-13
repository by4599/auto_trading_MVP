package com.trading.bucket;

import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 칸 자산 = 배분금 + 실현손익(TradeResult 또는 매도가 추정) + 보유 평가손익(신선한 잔고의 현재가).
 *
 * <p>판정 보류 원칙: 값을 모르면 0으로 꾸미지 않고 그 회차를 건너뛴다(조용한 통과·헛발동 모두 금지).
 * 날짜 사실관계(시스템 도구 확인): 2026-10-14 수.
 */
@DisplayName("SleeveEquityCalculator — 칸 자산 계산과 판정 보류")
class SleeveEquityCalculatorTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private final PositionRepository positions = mock(PositionRepository.class);
    private final List<Position> held = new ArrayList<>();
    private SleeveRealized trendRealized = SleeveRealized.none();
    private SleeveEquityCalculator sut;

    @BeforeEach
    void setUp() {
        when(positions.findAll()).thenReturn(held);
        Clock clock = Clock.fixed(TODAY.atTime(10, 0).atZone(KST).toInstant(), KST);
        BucketProperties props = new BucketProperties(true, "2026-07-20",
                10_000_000, 10_000_000, 10_000_000, 4_000_000, false, false, true);
        SleeveRealizedSource source = (bucket, from, to) ->
                bucket == StrategyBucket.TREND ? trendRealized : SleeveRealized.none();
        SleeveRealizedLedger ledger = new SleeveRealizedLedger(source,
                new SleeveStateStore(InMemoryPortfolioState.create(new HashMap<>())), props, clock);
        sut = new SleeveEquityCalculator(props, positions, ledger, clock);
    }

    private void hold(String code, StrategyBucket bucket, int qty, double avg) {
        Position p = Position.empty(code);
        p.assignBucketIfAbsent(bucket);
        p.applyBuy(qty, avg);
        held.add(p);
    }

    private static Account fresh(Account.PositionSnapshot... snapshots) {
        return new Account(10_000_000, 0.0, 0, List.of(snapshots));
    }

    private static Account.PositionSnapshot priced(String code, int qty, double avg, double current) {
        return new Account.PositionSnapshot(code, qty, avg, current);
    }

    @Test
    @DisplayName("거래도 보유도 없으면 칸 자산 = 배분금(400만)")
    void empty_sleeve_equals_allocation() {
        SleeveEquityCalculator.Result r = sut.compute(StrategyBucket.TREND, fresh());

        assertThat(r.isDeferred()).isFalse();
        assertThat(r.equity().equity()).isEqualTo(4_000_000);
    }

    @Test
    @DisplayName("실현손익(기록·추정)을 더하고, 측정 불가 거래는 0원으로 넣되 개수를 남긴다")
    void realized_pnl_and_unmeasurable_count() {
        trendRealized = new SleeveRealized(-150_000, 1, 2, 3, LocalDate.of(2026, 10, 2));

        SleeveEquityCalculator.Result r = sut.compute(StrategyBucket.TREND, fresh());

        assertThat(r.equity().equity()).isEqualTo(3_850_000);
        assertThat(r.equity().realized().unmeasurableCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("보유 평가손익 = 수량 × (현재가 − 평단), 현재가는 신선한 잔고에서")
    void unrealized_from_fresh_snapshot() {
        hold("005930", StrategyBucket.TREND, 10, 70_000);

        SleeveEquityCalculator.Result r = sut.compute(StrategyBucket.TREND,
                fresh(priced("005930", 10, 70_000, 66_000)));

        assertThat(r.equity().unrealized()).isEqualTo(-40_000);
        assertThat(r.equity().equity()).isEqualTo(3_960_000);
    }

    @Test
    @DisplayName("다른 칸의 보유는 섞이지 않고, 이름표 없는 보유는 VB로 센다")
    void other_bucket_holdings_are_excluded() {
        hold("000660", StrategyBucket.VB, 5, 100_000);
        Position legacy = Position.empty("035420");
        legacy.applyBuy(2, 200_000);   // 이름표 없음 → VB
        held.add(legacy);
        Account account = fresh(priced("000660", 5, 100_000, 90_000), priced("035420", 2, 200_000, 210_000));

        assertThat(sut.compute(StrategyBucket.TREND, account).equity().equity()).isEqualTo(4_000_000);
        assertThat(sut.compute(StrategyBucket.VB, account).equity().unrealized())
                .isEqualTo(-50_000 + 20_000);
    }

    @Test
    @DisplayName("보유가 있는데 잔고가 낡았으면 판정 보류 — 옛 가격으로 계산하지 않는다")
    void stale_snapshot_with_holdings_is_deferred() {
        hold("005930", StrategyBucket.TREND, 10, 70_000);

        SleeveEquityCalculator.Result r = sut.compute(StrategyBucket.TREND,
                fresh(priced("005930", 10, 70_000, 66_000)).asStale());

        assertThat(r.isDeferred()).isTrue();
        assertThat(r.deferredReason()).contains("낡");
    }

    @Test
    @DisplayName("보유가 없으면 잔고가 낡아도 계산할 수 있다 — 현재가가 필요 없다")
    void stale_snapshot_without_holdings_still_computes() {
        assertThat(sut.compute(StrategyBucket.TREND, fresh().asStale()).isDeferred()).isFalse();
        assertThat(sut.compute(StrategyBucket.TREND, null).isDeferred()).isFalse();
    }

    @Test
    @DisplayName("DB엔 있는데 증권사 잔고에 없거나 수량이 다르거나 현재가가 없으면 판정 보류")
    void mismatched_holdings_are_deferred() {
        hold("005930", StrategyBucket.TREND, 10, 70_000);

        assertThat(sut.compute(StrategyBucket.TREND, fresh()).deferredReason()).contains("005930");
        assertThat(sut.compute(StrategyBucket.TREND,
                fresh(priced("005930", 4, 70_000, 66_000))).isDeferred()).isTrue();
        assertThat(sut.compute(StrategyBucket.TREND,
                fresh(priced("005930", 10, 70_000, 0))).isDeferred()).isTrue();
    }

    @Test
    @DisplayName("오늘 판 거래의 매도가를 아직 모르면(분봉은 장 마감 뒤 쌓임) 오늘은 판정 보류")
    void unpriced_sale_today_defers() {
        trendRealized = new SleeveRealized(0, 0, 0, 1, TODAY);

        SleeveEquityCalculator.Result r = sut.compute(StrategyBucket.TREND, fresh());

        assertThat(r.isDeferred()).isTrue();
        assertThat(r.deferredReason()).contains("오늘");
    }

    @Test
    @DisplayName("지난날의 측정 불가는 보류하지 않는다 — 영영 못 구하는 값이라 0원으로 넣고 개수만 보고한다")
    void past_unmeasurable_does_not_defer() {
        trendRealized = new SleeveRealized(0, 0, 0, 1, TODAY.minusDays(1));

        assertThat(sut.compute(StrategyBucket.TREND, fresh()).isDeferred()).isFalse();
    }
}
