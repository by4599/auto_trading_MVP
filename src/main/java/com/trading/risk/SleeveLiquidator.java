package com.trading.risk;

import com.trading.bucket.StrategyBucket;
import com.trading.order.OrderEngine;
import com.trading.order.OrderHistoryRepository;
import com.trading.order.OrderSide;
import com.trading.order.OrderStatus;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 잠긴 칸의 보유 정리 — <b>평시 매도 경로</b>(Signal → RiskEngine → OrderEngine)로 판다.
 *
 * <p>계좌 전체 청산 상태머신({@link LiquidationService})은 쓰지 않는다 — 그것은 계좌 전량 청산·Trim 전용이고
 * (CLAUDE.md 규칙 5), 칸 하나를 정리하는 일은 타임컷({@code TimeCutScheduler})과 같은 평시 매도다.
 * 매도 수량은 OrderEngine이 그 종목의 보유 전량으로 정한다.
 *
 * <p><b>이중 매도 방지</b> — 잠긴 칸은 매 회차(1분) 다시 들르므로, 아래 둘 중 하나면 이번에는 내지 않는다:
 * <ol>
 *   <li>같은 종목에 미체결 매도(접수·부분체결)가 있다 — 앞 주문이 아직 살아 있다</li>
 *   <li>신선한 증권사 잔고에 그 종목이 없다 — 앞 주문이 체결됐는데 DB가 아직 모르는 중간 상태다
 *       (모의 체결조회 결함으로 매도 체결은 약 10분 뒤 대사로 들어온다, CLAUDE.md 결함 5)</li>
 * </ol>
 * 앞 주문이 실패(FAILED)·취소로 끝나 DB에 보유가 남아 있으면 다음 회차에 다시 낸다.
 */
@Component
@Profile("paper")
public class SleeveLiquidator {

    private static final Logger log = LoggerFactory.getLogger(SleeveLiquidator.class);

    static final String STRATEGY_NAME = "SleeveDrawdownCap";

    private final PositionRepository positionRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final RiskEngine riskEngine;
    private final OrderEngine orderEngine;

    public SleeveLiquidator(PositionRepository positionRepository,
                            OrderHistoryRepository orderHistoryRepository,
                            RiskEngine riskEngine,
                            OrderEngine orderEngine) {
        this.positionRepository = positionRepository;
        this.orderHistoryRepository = orderHistoryRepository;
        this.riskEngine = riskEngine;
        this.orderEngine = orderEngine;
    }

    /**
     * @param account 이번 회차의 <b>신선한</b> 잔고 스냅샷 (감시기가 신선할 때만 부른다)
     * @return 이번에 매도를 낸 종목 수
     */
    public int sellHoldings(StrategyBucket bucket, Account account) {
        int sent = 0;
        for (Position pos : holdingsOf(bucket)) {
            try {
                if (sellOne(bucket, pos, account)) sent++;
            } catch (Exception e) {
                // 한 종목의 실패가 나머지 정리를 멈추지 않는다 (종목별 예외 격리 — 타임컷과 같은 원칙)
                log.error("[칸 정리] {} 매도 실패 — 계속 진행: {}", bucket, pos.getStockCode(), e);
            }
        }
        return sent;
    }

    private boolean sellOne(StrategyBucket bucket, Position pos, Account account) {
        String code = pos.getStockCode();
        if (hasPendingSell(code)) {
            log.info("[칸 정리] {} {} 미체결 매도 대기 중 — 다시 내지 않음(이중 매도 방지)", bucket, code);
            return false;
        }
        if (brokerQuantity(account, code) <= 0) {
            log.info("[칸 정리] {} {} 증권사 보유 0 — 앞 매도가 체결된 것으로 보고 다시 내지 않음", bucket, code);
            return false;
        }

        Signal signal = Signal.sell(code, STRATEGY_NAME);
        RiskResult result = riskEngine.check(signal, account);
        if (!result.isPass()) {
            log.warn("[칸 정리] {} {} RiskEngine 거부: {}", bucket, code, result.getReason());
            return false;
        }
        orderEngine.execute(signal);
        log.warn("[칸 정리] {} 칸 잠금 — 매도 접수: {} {}주", bucket, code, pos.getQuantity());
        return true;
    }

    private List<Position> holdingsOf(StrategyBucket bucket) {
        return positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .filter(p -> StrategyBucket.orDefault(p.getBucket()) == bucket)
                .toList();
    }

    private static int brokerQuantity(Account account, String code) {
        return account.getPositions().stream()
                .filter(s -> s.stockCode().equals(code))
                .mapToInt(Account.PositionSnapshot::quantity)
                .sum();
    }

    private boolean hasPendingSell(String stockCode) {
        return orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.ACCEPTED)
                || orderHistoryRepository.existsByStockCodeAndSideAndStatus(
                        stockCode, OrderSide.SELL, OrderStatus.PARTIAL_FILLED);
    }
}
