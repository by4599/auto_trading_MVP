package com.trading.order;

import com.trading.market.AtrCalculator;
import com.trading.market.MarketDataService;
import com.trading.position.PositionManager;
import com.trading.risk.RiskLimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.OptionalDouble;

/**
 * R 기반 주문 수량 역산 (방법론 §4.3) — ORD_QTY=1 고정을 대체한다.
 *
 *   1R(허용 손실) = 계좌 × 1%
 *   손절폭        = ATR(14) × 1.5
 *   주문 수량     = floor(1R ÷ 손절폭)   — 항상 내림 (올림은 허용 손실 초과)
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

    public OrderSizingService(MarketDataService marketDataService,
                              PositionManager positionManager,
                              AtrCalculator atrCalculator,
                              RiskLimitsProperties limits) {
        this.marketDataService = marketDataService;
        this.positionManager = positionManager;
        this.atrCalculator = atrCalculator;
        this.limits = limits;
    }

    public SizingResult sizeBuy(String stockCode) {
        OptionalDouble atrOpt;
        try {
            atrOpt = atrCalculator.atr(
                    marketDataService.getDailyCandles(stockCode, AtrCalculator.PERIOD + 1));
        } catch (Exception e) {
            return SizingResult.skip("일봉 조회 실패: " + e.getMessage());
        }
        if (atrOpt.isEmpty()) {
            return SizingResult.skip("ATR 산출 불가 (일봉 " + (AtrCalculator.PERIOD + 1) + "개 미만)");
        }

        double equity = positionManager.snapshotAccount().getTotalAssetValue();
        if (equity <= 0) {
            return SizingResult.skip("총자산 조회 불가 (equity<=0) — 사이징 불성립");
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

        log.info("[Sizing] {} 수량={} (1R={}, 손절폭={}, 실제리스크={})",
                stockCode, quantity, String.format("%.0f", oneR),
                String.format("%.0f", stopDistance), String.format("%.0f", actualRisk));
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
