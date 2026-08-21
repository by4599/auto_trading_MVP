package com.trading.mirror;

import com.trading.position.Account;
import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResult;
import com.trading.position.TradeResultRepository;
import com.trading.risk.TradingStatusManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 외부로 내보낼 운영 스냅샷을 조립한다 — 조회만 하고 아무 상태도 바꾸지 않는다.
 *
 * 보유 현황의 원천은 브로커 잔고 스냅샷(Account)이고, 손절선·지갑 칸은 Position 테이블에서
 * 채운다. Account는 3초 캐시라 장중에는 RiskMonitor가 이미 받아둔 값을 그대로 재사용한다
 * (미러 때문에 KIS 호출이 새로 나가지 않는다 — 전송 시점을 장중으로 제한하는 이유).
 */
@Component
@Profile("paper")
public class MirrorSnapshotAssembler {

    private final PositionManager          positionManager;
    private final PositionRepository       positionRepository;
    private final TradeResultRepository    tradeResultRepository;
    private final PortfolioStateRepository portfolioStateRepository;
    private final TradingStatusManager     statusManager;
    private final SupabaseProperties       props;
    private final Clock                    clock;

    public MirrorSnapshotAssembler(PositionManager          positionManager,
                                   PositionRepository       positionRepository,
                                   TradeResultRepository    tradeResultRepository,
                                   PortfolioStateRepository portfolioStateRepository,
                                   TradingStatusManager     statusManager,
                                   SupabaseProperties       props,
                                   Clock                    clock) {
        this.positionManager          = positionManager;
        this.positionRepository       = positionRepository;
        this.tradeResultRepository    = tradeResultRepository;
        this.portfolioStateRepository = portfolioStateRepository;
        this.statusManager            = statusManager;
        this.props                    = props;
        this.clock                    = clock;
    }

    public MirrorSnapshot assemble() {
        Account account = positionManager.snapshotAccount();
        Map<String, Position> stored = positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .collect(Collectors.toMap(Position::getStockCode, Function.identity(), (a, b) -> a));

        List<MirrorSnapshot.Holding> holdings = account.getPositions().stream()
                .filter(p -> p.quantity() > 0)
                .map(p -> toHolding(p, stored.get(p.stockCode())))
                .toList();

        long unrealized = holdings.stream().mapToLong(MirrorSnapshot.Holding::unrealizedPnl).sum();

        return new MirrorSnapshot(
                props.getSnapshotId(),
                ZonedDateTime.now(clock).toOffsetDateTime().toString(),
                statusManager.getCurrentMode().name(),
                account.isFresh(),
                Math.round(account.getTotalAssetValue()),
                Math.round(account.getDailyPnlPercent() * 100.0 * 100.0) / 100.0,  // 비율 → 퍼센트(소수 2자리)
                account.getConsecutiveLossCount(),
                runStreakDays(),
                unrealized,
                realizedPnlToday(),
                holdings);
    }

    private MirrorSnapshot.Holding toHolding(Account.PositionSnapshot snapshot, Position stored) {
        long averagePrice = Math.round(snapshot.averagePrice());
        long currentPrice = Math.round(snapshot.currentPrice());
        Long stopPrice = (stored != null && stored.getStopPrice() != null)
                ? Math.round(stored.getStopPrice()) : null;
        String bucket = (stored != null && stored.getBucket() != null)
                ? stored.getBucket().name() : null;

        return new MirrorSnapshot.Holding(
                snapshot.stockCode(),
                snapshot.quantity(),
                averagePrice,
                currentPrice,
                stopPrice,
                (currentPrice - averagePrice) * (long) snapshot.quantity(),
                bucket);
    }

    private int runStreakDays() {
        return portfolioStateRepository.findById(PortfolioState.KEY_RUN_STREAK_DAYS)
                .map(state -> (int) state.getStateValue())
                .orElse(0);
    }

    private long realizedPnlToday() {
        return Math.round(tradeResultRepository.findByTradeDate(LocalDate.now(clock)).stream()
                .mapToDouble(TradeResult::getRealizedPnl)
                .sum());
    }
}
