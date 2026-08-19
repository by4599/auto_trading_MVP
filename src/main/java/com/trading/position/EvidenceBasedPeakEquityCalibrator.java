package com.trading.position;

import com.trading.NotificationService;
import com.trading.risk.RiskLimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.OptionalDouble;

/**
 * daily_equity(거래일별 시작 자산) 실측 기록으로 전고점을 검증하는 모의투자용 구현체.
 *
 * 유일하게 남아 있는 자산 실측 기록이 daily_equity다. 이를 근거로 "증거상 가능한 최대 자산
 * (= 검증된 최대 자산 × (1 + 장중 허용폭))"을 계산해 ① 기동 시 저장값 교정 ② 매 틱 갱신 차단에 쓴다.
 */
@Component
@Profile("paper")
public class EvidenceBasedPeakEquityCalibrator implements PeakEquityCalibrator {

    private static final Logger log = LoggerFactory.getLogger(EvidenceBasedPeakEquityCalibrator.class);

    /** 국내 주식 일일 가격제한폭(+30%) — 하루에 오를 수 있는 주식 평가액의 물리적 상한 */
    static final double DAILY_PRICE_LIMIT = 0.30;

    private final DailyEquityRepository dailyEquityRepository;
    private final PortfolioStateRepository stateRepository;
    private final RiskLimitsProperties limits;
    private final NotificationService notifier;

    public EvidenceBasedPeakEquityCalibrator(DailyEquityRepository dailyEquityRepository,
                                             PortfolioStateRepository stateRepository,
                                             RiskLimitsProperties limits,
                                             NotificationService notifier) {
        this.dailyEquityRepository = dailyEquityRepository;
        this.stateRepository = stateRepository;
        this.limits = limits;
        this.notifier = notifier;
    }

    /**
     * 장중 미기록 고점 허용폭 — 리스크 파라미터에서 실시간으로 유도한다.
     *
     * daily_equity는 거래일 "시작" 자산만 남기므로, 장중에 올랐다가 다음 날 시작 전에 되밀린
     * 진짜 고점은 기록에 없다. 그 여지를 남기되 물리적 상한을 넘지 않게 잡는다:
     * 최대 주식 노출 = min(1.0, 종목당 비중 한도 × 최대 보유 종목수), 국내 일일 가격제한폭 +30%
     * → 하루에 오를 수 있는 총자산 = 노출 × 0.30.
     *
     * 두 파라미터는 설정 UI(TradingParamService → RiskLimitsProperties)에서 사람이 바꿀 수 있으므로
     * 상수로 고정하지 않고 매번 읽는다 — 넉넉하게 설정한 계좌에서 정당한 신고점을 오판하지 않기 위함.
     * (기본값 0.10 × 5종목 = 노출 50% → 허용폭 15%)
     */
    double intradayHeadroom() {
        double exposure = Math.min(1.0, limits.getMaxPositionWeight() * limits.getMaxPositionCount());
        return exposure * DAILY_PRICE_LIMIT;
    }

    @Override
    public double calibrate(double storedPeak) {
        OptionalDouble ceiling = plausibleCeiling();
        if (ceiling.isEmpty() || storedPeak <= ceiling.getAsDouble()) return storedPeak;

        double clamped = ceiling.getAsDouble();
        // 원값은 별도 키로 남긴다 — 오염 원인 조사의 유일한 단서이므로 덮어쓰기 전에 보존한다.
        stateRepository.save(PortfolioState.of(
                PortfolioState.KEY_PEAK_EQUITY_RAW_BEFORE_CALIBRATION, storedPeak));

        // 전고점 하향은 MDD를 작게 만들어 안전장치를 느슨하게 하는 방향이다.
        // 그래서 "검증된 최대 자산"까지 깎지 않고 상한까지만 클램프하고, 사람에게도 알린다.
        log.error("[PeakEquity] 저장된 전고점 {}이 실측 근거상 불가능 — 상한 {}로 클램프 (원값은 {} 키로 보존)",
                storedPeak, clamped, PortfolioState.KEY_PEAK_EQUITY_RAW_BEFORE_CALIBRATION);
        notifier.sendCritical(String.format(
                "🔧 [전고점 교정] 저장된 전고점 %,.0f원이 실측 근거상 불가능해 %,.0f원으로 낮췄습니다.%n"
                        + "근거: 실측 최대 자산 %,.0f원 × 장중 허용폭 %.0f%%%n"
                        + "원값은 %s 로 보존했습니다 — 원인 확인 전까지 매매 재개는 사람이 판단하세요.",
                storedPeak, clamped, verifiedMaxEquity().orElse(0), intradayHeadroom() * 100,
                PortfolioState.KEY_PEAK_EQUITY_RAW_BEFORE_CALIBRATION));
        return clamped;
    }

    @Override
    public boolean isImplausible(double equity) {
        OptionalDouble ceiling = plausibleCeiling();
        return ceiling.isPresent() && equity > ceiling.getAsDouble();
    }

    /** 증거상 가능한 최대 자산 — 실측 기록이 없으면 empty(판정 보류) */
    private OptionalDouble plausibleCeiling() {
        OptionalDouble verified = verifiedMaxEquity();
        if (verified.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(verified.getAsDouble() * (1 + intradayHeadroom()));
    }

    /** daily_equity 전체 이력의 최대 시작 자산 — 실제로 도달한 것이 확인된 유일한 자산 기록 */
    private OptionalDouble verifiedMaxEquity() {
        try {
            Double max = dailyEquityRepository.findMaxStartEquity();
            if (max == null || max <= 0) return OptionalDouble.empty();
            return OptionalDouble.of(max);
        } catch (Exception e) {
            log.warn("[PeakEquity] daily_equity 조회 실패 — 이번 검증 보류: {}", e.getMessage());
            return OptionalDouble.empty();
        }
    }
}
