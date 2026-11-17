package com.trading.position;

import com.trading.BackgroundAlertSender;
import com.trading.NotificationService;
import com.trading.market.MarketCalendarService;
import com.trading.risk.RiskLimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    /**
     * 같은 거래일에 전고점 알림을 <b>다시</b> 보내기 위한 추가 상승 문턱 (+1.0%).
     *
     * <p>전고점은 신고점을 찍는 날 1초 간격으로 조금씩 계속 올라가므로, 갱신 때마다 보내면
     * 하루에 수십 건이 간다. 반대로 2026-09-21 사고처럼 +7.94% 튀는 오염은 반드시 울려야 한다.
     * 이 문턱이 그 둘을 가른다 — 평범한 날은 1~2회, 오염은 즉시.
     */
    static final double RENOTIFY_RISE_THRESHOLD = 0.010;

    /** 보류 비우기 주기 — 개장하면 1분 안에 밀린 경보가 나간다 */
    private static final long FLUSH_INTERVAL_MS = 60_000;

    private static final DateTimeFormatter HELD_AT_FMT = DateTimeFormatter.ofPattern("M/d HH:mm");

    private final DailyEquityRepository dailyEquityRepository;
    private final PortfolioStateRepository stateRepository;
    private final RiskLimitsProperties limits;
    private final NotificationService notifier;
    private final Clock clock;
    private final MarketCalendarService marketCalendar;

    /**
     * 마지막으로 텔레그램을 보낸 거래일과 그때의 전고점.
     *
     * <p>메모리로 충분하다(리더 확정 2026-09-21): 재기동하면 그날 첫 발송이 한 번 더 갈 수 있는데,
     * 그 정도는 허용하고 DB 스키마를 늘리지 않는다. 발송 판정은 한 틱 안에서 읽고-쓰므로
     * {@code notifyPeakRaised}를 통째로 잠근다.
     */
    private LocalDate lastAlertDate;
    private double lastAlertPeak;

    /**
     * 장 밖에서 오른 만큼을 모아 두는 보류함 (감사 M-1).
     *
     * <p>{@code TelegramNotifier}는 거래일 09:00~15:30 밖이면 조용히 스킵한다(2026-08 결정).
     * 그런데 {@code ShadowPortfolio.tick()}은 장 밖에도 1초마다 돌고 잔고 스냅샷에 장중 게이트가
     * 없어 <b>08:30 자동 기동~09:00 구간의 갱신도 성립</b>한다. 실측된 오염·오독 2건이 둘 다
     * 장 밖이었으므로(09-09 세션 중 추정 · 09-11 17:17) 여기서 놓치면 탐지선이 사라진다.
     *
     * <p>값 하나가 아니라 <b>누적</b>이다 — 잘게 여러 번 오르면 합쳐 한 통으로 보내야
     * 실제 상승폭이 보인다.
     */
    private double pendingPrevious;      // 가장 이른 직전값
    private double pendingCurrent;       // 가장 높은 새값
    private int pendingCount;
    private LocalDateTime pendingFirstAt;
    private LocalDateTime pendingLastAt;
    private boolean pendingHeldOutOfHours;

    /** 전송을 1초 감시 루프 밖으로 빼는 전용 스레드 (감사 M-2 — 상세는 그 클래스 주석) */
    private final BackgroundAlertSender alertSender;

    /** "의심스러운 고점 거부" 하루 1회 경고 — 같은 전송 스레드를 쓴다 (결함 6, 2026-10-11) */
    private final SuspiciousPeakAlerter suspiciousPeakAlerter;

    public EvidenceBasedPeakEquityCalibrator(DailyEquityRepository dailyEquityRepository,
                                             PortfolioStateRepository stateRepository,
                                             RiskLimitsProperties limits,
                                             NotificationService notifier,
                                             Clock clock,
                                             MarketCalendarService marketCalendar) {
        this.dailyEquityRepository = dailyEquityRepository;
        this.stateRepository = stateRepository;
        this.limits = limits;
        this.notifier = notifier;
        this.clock = clock;
        this.marketCalendar = marketCalendar;
        this.alertSender = new BackgroundAlertSender(notifier::sendCritical, "peak-alert-sender");
        this.suspiciousPeakAlerter = new SuspiciousPeakAlerter(alertSender::send, clock, marketCalendar);
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

    /**
     * 전고점 경신을 사람에게 알린다 — 오염이 조용히 굳는 것을 막는 실시간 신호다.
     *
     * <p>2026-09-21 사고: 저장된 전고점이 실측 최고보다 7.94% 높은 값으로 오염돼 7거래일간
     * 매 개장 직후 강제청산이 발동했는데, 갱신 기록이 DEBUG 로그뿐이라 언제 오염됐는지 아무도 몰랐다.
     *
     * <p><b>발송은 조이고 로그는 조이지 않는다.</b> 되짚는 일은 {@code ShadowPortfolio}의 INFO
     * 로그가 갱신 때마다 전부 맡고, 텔레그램은 "지금 이상한 일이 일어났다"를 알리는 용도라
     * 아래 둘 중 하나일 때만 1회 나간다:
     * <ol>
     *   <li>그 거래일의 <b>첫 갱신</b>일 때</li>
     *   <li>그날 <b>직전 발송 시점의 전고점 대비 {@value #RENOTIFY_RISE_THRESHOLD}(+1.0%) 이상</b>
     *       더 올랐을 때</li>
     * </ol>
     *
     * <p>최초 확립(직전값 0)은 '경신'이 아니라 기준선이 처음 생긴 것이므로 보내지 않는다 —
     * 증가율도 정의되지 않는다. 전송 창(거래일 09:00~15:30) 판정은 TelegramNotifier가 한다.
     */
    @Override
    public synchronized void notifyPeakRaised(double previous, double current) {
        if (previous <= 0) return;
        if (!worthAlerting(current)) return;

        hold(previous, current);
        flushIfDuringMarketHours();
    }

    /**
     * 보류함을 비운다 — 장이 열려 있을 때만 나간다.
     *
     * <p>장 밖에 쌓인 경보를 개장 1분 안에 내보내는 유일한 경로다. 이 클래스가
     * {@code @Profile("paper")}라 백테스트에는 이 스케줄 자체가 생기지 않는다.
     */
    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public synchronized void flushPendingAlert() {
        flushIfDuringMarketHours();
    }

    /**
     * 알릴 만한 경신인가 — ① 그 거래일의 첫 갱신 ② 그날 직전 발송 대비 +1.0% 이상 추가 상승.
     * 도장({@code lastAlert*})은 <b>실제로 내보낼 때만</b> 찍는다: 장 밖에서 먼저 찍으면
     * 그날 "첫 갱신 1회" 보장만 소모되고 경보는 사라진다(감사 M-1).
     */
    private boolean worthAlerting(double current) {
        if (!LocalDate.now(clock).equals(lastAlertDate) || lastAlertPeak <= 0) return true;
        return (current - lastAlertPeak) / lastAlertPeak >= RENOTIFY_RISE_THRESHOLD;
    }

    /** 보류함 누적 — 가장 이른 직전값과 가장 높은 새값을 남긴다 */
    private void hold(double previous, double current) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (pendingCount == 0) {
            pendingPrevious = previous;
            pendingFirstAt  = now;
        }
        pendingCurrent = Math.max(pendingCurrent, current);
        pendingLastAt  = now;
        pendingCount++;
        if (!marketCalendar.isDuringMarketHoursNow()) pendingHeldOutOfHours = true;
    }

    private void flushIfDuringMarketHours() {
        if (pendingCount == 0) return;
        if (!marketCalendar.isDuringMarketHoursNow()) return;

        String message = pendingMessage();
        lastAlertDate = LocalDate.now(clock);
        lastAlertPeak = pendingCurrent;
        clearPending();
        alertSender.send(message);
    }

    private void clearPending() {
        pendingPrevious = 0;
        pendingCurrent  = 0;
        pendingCount    = 0;
        pendingFirstAt  = null;
        pendingLastAt   = null;
        pendingHeldOutOfHours = false;
    }

    /** 장 밖에서 모인 것이면 "언제·몇 번"을 앞에 붙여 사람이 지금 오른 것으로 오해하지 않게 한다 */
    private String pendingMessage() {
        double rise = (pendingCurrent - pendingPrevious) / pendingPrevious * 100;
        String heldNote = pendingHeldOutOfHours
                ? String.format("장 밖(%s ~ %s, %d회)에 오른 것을 지금 알립니다.%n",
                        HELD_AT_FMT.format(pendingFirstAt), HELD_AT_FMT.format(pendingLastAt), pendingCount)
                : "";
        return String.format(
                "📈 [전고점 경신] %s%,.0f원 → %,.0f원 (+%.2f%%)%n"
                        + "강제정지 문턱도 %,.0f원으로 올라갑니다 (전고점 대비 -%.0f%%).%n"
                        + "짐작보다 큰 폭이면 잔고 값이 튄 것일 수 있습니다 — 로그를 확인하세요.",
                heldNote, pendingPrevious, pendingCurrent, rise,
                pendingCurrent * (1 - limits.getMddLimit()), limits.getMddLimit() * 100);
    }

    /** 의심스러운 고점 거부 경고 — 하루 1회, 장중에만, 감시 스레드 밖 전송 (상세는 {@link SuspiciousPeakAlerter}) */
    @Override
    public void notifySuspiciousPeakRejected(SuspiciousPeakReason reason, double rejectedEquity,
                                             double currentPeak, EquityCrossCheck check) {
        suspiciousPeakAlerter.alert(reason, rejectedEquity, currentPeak, check);
    }

    @PreDestroy
    void shutdownSender() {
        alertSender.close();
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
