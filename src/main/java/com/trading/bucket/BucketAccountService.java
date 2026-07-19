package com.trading.bucket;

import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.position.TradeResult;
import com.trading.position.TradeResultRepository;
import org.springframework.stereotype.Component;

/**
 * 칸별 장부 계산 — 칸 나누기의 단일 계산 출처.
 *
 *   칸 자산   = 배분금 + 실험 시작일 이후 그 칸의 실현손익 누적 (미실현 손익은 v1 미포함)
 *   투입 원가 = 그 칸 보유 포지션의 수량 × 평균단가 합
 *   가용 현금 = 칸 자산 − 투입 원가
 *
 * 포지션·실적 행 수가 작아(최대 종목 20, 모의 실적) findAll 후 자바 필터로 충분하다.
 */
@Component
public class BucketAccountService {

    private final BucketProperties properties;
    private final PositionRepository positionRepository;
    private final TradeResultRepository tradeResultRepository;

    public BucketAccountService(BucketProperties properties,
                                PositionRepository positionRepository,
                                TradeResultRepository tradeResultRepository) {
        this.properties = properties;
        this.positionRepository = positionRepository;
        this.tradeResultRepository = tradeResultRepository;
    }

    /**
     * 사이징용 칸 자산. 칸 나누기가 꺼져 있으면(백테스트 등) 계좌 전체 자산을
     * 그대로 돌려줘 기존 동작을 보존한다.
     */
    public double sizingEquity(StrategyBucket bucket, double accountEquity) {
        if (!properties.isEnabled()) return accountEquity;
        return equity(bucket);
    }

    public double equity(StrategyBucket bucket) {
        return properties.allocationOf(bucket) + realizedPnl(bucket);
    }

    public double availableCash(StrategyBucket bucket) {
        return equity(bucket) - investedCost(bucket);
    }

    private double realizedPnl(StrategyBucket bucket) {
        return tradeResultRepository.findAll().stream()
                .filter(r -> !r.getTradeDate().isBefore(properties.getExperimentStart()))
                .filter(r -> StrategyBucket.orDefault(r.getBucket()) == bucket)
                .mapToDouble(TradeResult::getRealizedPnl)
                .sum();
    }

    private double investedCost(StrategyBucket bucket) {
        return positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .filter(p -> StrategyBucket.orDefault(p.getBucket()) == bucket)
                .mapToDouble(p -> p.getQuantity() * p.getAveragePrice())
                .sum();
    }
}
