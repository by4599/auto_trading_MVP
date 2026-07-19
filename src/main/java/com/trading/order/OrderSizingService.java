package com.trading.order;

import com.trading.bucket.BucketAccountService;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.StrategyBucket;
import com.trading.market.AtrCalculator;
import com.trading.market.Candle;
import com.trading.market.MarketDataService;
import com.trading.position.PositionManager;
import com.trading.risk.RiskLimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalDouble;

/**
 * R 기반 주문 수량 역산 (방법론 §4.3) — ORD_QTY=1 고정을 대체한다.
 *
 *   1R(허용 손실) = 자산 × 1%
 *   손절폭        = ATR(14) × 1.5
 *   주문 수량     = floor(1R ÷ 손절폭)   — 항상 내림 (올림은 허용 손실 초과)
 *
 * 지갑 칸 실험(trading.bucket.enabled=true, paper 전용): "자산"은 계좌 전체가 아니라
 * 그 칸의 자산(배분금+실현손익)이고, 칸의 가용 현금을 넘는 수량은 현금 한도로 깎는다.
 * 칸 나누기가 꺼져 있으면(백테스트 포함) 기존과 완전히 동일하게 동작한다.
 *
 * 스킵 규칙: 수량 0이거나, 내림으로 인한 실제 리스크가 1R 대비 ±20%를 벗어나면
 * 진입하지 않는다 — 왜곡된 채 진입하는 것보다 진입하지 않는 것이 규칙에 충실하다.
 */
@Component
public class OrderSizingService {

    private static final Logger log = LoggerFactory.getLogger(OrderSizingService.class);

    private final MarketDataService marketDataService;
    private final PositionManager positionManager;
    private final AtrCalculator atrCalculator;
    private final RiskLimitsProperties limits;
    private final BucketProperties bucketProperties;
    private final BucketAccountService bucketAccountService;

    public OrderSizingService(MarketDataService marketDataService,
                              PositionManager positionManager,
                              AtrCalculator atrCalculator,
                              RiskLimitsProperties limits,
                              BucketProperties bucketProperties,
                              BucketAccountService bucketAccountService) {
        this.marketDataService = marketDataService;
        this.positionManager = positionManager;
        this.atrCalculator = atrCalculator;
        this.limits = limits;
        this.bucketProperties = bucketProperties;
        this.bucketAccountService = bucketAccountService;
    }

    public SizingResult sizeBuy(String stockCode) {
        return sizeBuy(stockCode, StrategyBucket.VB);
    }

    public SizingResult sizeBuy(String stockCode, StrategyBucket bucket) {
        List<Candle> dailyCandles;
        try {
            dailyCandles = marketDataService.getDailyCandles(stockCode, AtrCalculator.PERIOD + 1);
        } catch (Exception e) {
            return SizingResult.skip("일봉 조회 실패: " + e.getMessage());
        }
        OptionalDouble atrOpt = atrCalculator.atr(dailyCandles);
        if (atrOpt.isEmpty()) {
            return SizingResult.skip("ATR 산출 불가 (일봉 " + (AtrCalculator.PERIOD + 1) + "개 미만)");
        }

        double accountEquity = positionManager.snapshotAccount().getTotalAssetValue();
        double equity = bucketAccountService.sizingEquity(bucket, accountEquity);
        if (equity <= 0) {
            return SizingResult.skip("자산 조회 불가 (equity<=0) — 사이징 불성립");
        }

        double stopDistance = atrOpt.getAsDouble() * limits.getAtrStopMultiplier();
        double oneR = equity * limits.getRiskFractionPerTrade();

        int quantity = (int) Math.floor(oneR / stopDistance);
        if (quantity < 1) {
            return SizingResult.skip(String.format(
                    "1주 리스크(%.0f)가 1R(%.0f) 초과 — 고가 종목 스킵", stopDistance, oneR));
        }

        double actualRisk = quantity * stopDistance;
        double distortion = Math.abs(actualRisk - oneR) / oneR;
        if (distortion > limits.getSizingMaxDistortion()) {
            return SizingResult.skip(String.format(
                    "단주 내림 왜곡 %.0f%% > 한도 %.0f%% — 스킵",
                    distortion * 100, limits.getSizingMaxDistortion() * 100));
        }

        // 칸 가용 현금 한도 — 최근 종가로 매수 대금을 추정해 칸 예산 초과분을 깎는다
        if (bucketProperties.isEnabled()) {
            double lastClose = dailyCandles.get(dailyCandles.size() - 1).close();
            double availableCash = bucketAccountService.availableCash(bucket);
            int affordable = (int) Math.floor(availableCash / lastClose);
            if (affordable < 1) {
                return SizingResult.skip(String.format(
                        "칸(%s) 가용 현금 부족: %.0f원 < 1주(%.0f원)",
                        bucket, availableCash, lastClose));
            }
            if (affordable < quantity) {
                log.info("[Sizing] {} 칸({}) 현금 한도로 수량 축소: {} → {}",
                        stockCode, bucket, quantity, affordable);
                quantity = affordable;
            }
        }

        log.info("[Sizing] {} 수량={} (칸={}, 1R={}, 손절폭={}, 실제리스크={})",
                stockCode, quantity, bucket, String.format("%.0f", oneR),
                String.format("%.0f", stopDistance), String.format("%.0f", quantity * stopDistance));
        return SizingResult.ok(quantity, stopDistance);
    }

    /** executable=false면 quantity/stopDistance는 무의미, skipReason만 유효 */
    public record SizingResult(boolean executable, int quantity, double stopDistance, String skipReason) {
        static SizingResult ok(int quantity, double stopDistance) {
            return new SizingResult(true, quantity, stopDistance, null);
        }
        static SizingResult skip(String reason) {
            return new SizingResult(false, 0, 0.0, reason);
        }
    }
}
