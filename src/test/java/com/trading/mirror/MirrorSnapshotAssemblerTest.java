package com.trading.mirror;

import com.trading.bucket.StrategyBucket;
import com.trading.position.Account;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResult;
import com.trading.position.TradeResultRepository;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 외부로 내보낼 운영 스냅샷 조립.
 *
 * 날짜 사실관계 (시스템 도구로 검증): 2026-08-20 목요일.
 * 목킹은 리포지토리·PositionManager 등 인터페이스만 (Java 25 인라인 목 제약).
 */
@DisplayName("MirrorSnapshotAssembler — 운영 스냅샷 조립")
class MirrorSnapshotAssemblerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate THU_0820 = LocalDate.of(2026, 8, 20);

    private final Clock clock = Clock.fixed(THU_0820.atTime(10, 0).atZone(KST).toInstant(), KST);

    private final PositionManager          positionManager  = mock(PositionManager.class);
    private final PositionRepository       positionRepo     = mock(PositionRepository.class);
    private final TradeResultRepository    tradeResultRepo  = mock(TradeResultRepository.class);
    private final PortfolioStateRepository stateRepo        = mock(PortfolioStateRepository.class);

    private MirrorSnapshotAssembler assembler() {
        return new MirrorSnapshotAssembler(positionManager, positionRepo, tradeResultRepo,
                stateRepo, new TradingStatusManager(), new SupabaseProperties(), clock);
    }

    private static Account accountWith(double dailyPnlRatio, Account.PositionSnapshot... holdings) {
        return new Account(50_000_000.0, dailyPnlRatio, 1, List.of(holdings));
    }

    private static Position storedPosition(String code, int qty, double avg,
                                           Double stopPrice, StrategyBucket bucket) {
        Position p = Position.empty(code);
        p.applyBuy(qty, avg);
        if (stopPrice != null) p.armStopLoss(stopPrice);
        if (bucket != null) p.assignBucketIfAbsent(bucket);
        return p;
    }

    @Test
    @DisplayName("보유 종목에 손절선·지갑 칸이 결합되고 평가손익이 계산된다")
    void combinesBrokerHoldingWithStoredStopAndBucket() {
        when(positionManager.snapshotAccount()).thenReturn(
                accountWith(0.0, new Account.PositionSnapshot("005930", 10, 70_000, 72_000)));
        when(positionRepo.findAll()).thenReturn(
                List.of(storedPosition("005930", 10, 70_000, 68_000.0, StrategyBucket.VB)));

        MirrorSnapshot snapshot = assembler().assemble();

        assertThat(snapshot.holdings()).hasSize(1);
        MirrorSnapshot.Holding holding = snapshot.holdings().get(0);
        assertThat(holding.stockCode()).isEqualTo("005930");
        assertThat(holding.quantity()).isEqualTo(10);
        assertThat(holding.averagePrice()).isEqualTo(70_000);
        assertThat(holding.currentPrice()).isEqualTo(72_000);
        assertThat(holding.stopPrice()).isEqualTo(68_000);
        assertThat(holding.bucket()).isEqualTo("VB");
        assertThat(holding.unrealizedPnl()).isEqualTo(20_000);   // (72000-70000) * 10
        assertThat(snapshot.unrealizedPnl()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("Position 테이블에 없는 종목도 손절선 null로 담기고 전송 payload가 만들어진다")
    void handlesHoldingMissingFromPositionTable() {
        when(positionManager.snapshotAccount()).thenReturn(
                accountWith(0.0, new Account.PositionSnapshot("012330", 3, 250_000, 249_000)));
        when(positionRepo.findAll()).thenReturn(List.of());

        MirrorSnapshot snapshot = assembler().assemble();

        assertThat(snapshot.holdings().get(0).stopPrice()).isNull();
        assertThat(snapshot.holdings().get(0).bucket()).isNull();
        assertThat(snapshot.holdings().get(0).unrealizedPnl()).isEqualTo(-3_000);

        // null이 섞인 행도 Map으로 접히는지 (Map.of였다면 NPE)
        assertThatCode(snapshot::toRow).doesNotThrowAnyException();
        Map<String, Object> row = snapshot.toRow();
        assertThat(row).containsEntry("id", "paper").containsKey("holdings");
    }

    @Test
    @DisplayName("일일 등락률은 비율에서 사람이 읽는 퍼센트로 환산된다")
    void convertsDailyPnlRatioToPercent() {
        when(positionManager.snapshotAccount()).thenReturn(accountWith(0.0153));

        assertThat(assembler().assemble().dailyPnlPercent()).isEqualTo(1.53);
    }

    @Test
    @DisplayName("오늘 실현손익과 연속 무중단 가동일수를 함께 담는다")
    void includesRealizedPnlAndRunStreak() {
        when(positionManager.snapshotAccount()).thenReturn(accountWith(0.0));
        when(tradeResultRepo.findByTradeDate(THU_0820)).thenReturn(List.of(
                TradeResult.live("005930", 10, 70_000, 71_000),   // +10,000
                TradeResult.live("012330", 2, 250_000, 249_500))); //  -1,000
        when(stateRepo.findById(PortfolioState.KEY_RUN_STREAK_DAYS))
                .thenReturn(Optional.of(PortfolioState.of(PortfolioState.KEY_RUN_STREAK_DAYS, 3)));

        MirrorSnapshot snapshot = assembler().assemble();

        assertThat(snapshot.realizedPnlToday()).isEqualTo(9_000);
        assertThat(snapshot.runStreakDays()).isEqualTo(3);
        assertThat(snapshot.consecutiveLossCount()).isEqualTo(1);
        assertThat(snapshot.tradingMode()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("낡은(폴백) 잔고 스냅샷은 accountFresh=false로 표시해 보낸다")
    void marksStaleAccountSnapshot() {
        when(positionManager.snapshotAccount()).thenReturn(accountWith(0.0).asStale());
        when(tradeResultRepo.findByTradeDate(any())).thenReturn(List.of());

        assertThat(assembler().assemble().accountFresh()).isFalse();
    }
}
