package com.trading.position;

import com.trading.market.MarketCalendarService;
import com.trading.position.PeakEquityCalibrator.SuspiciousPeakReason;

import java.time.Clock;
import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * "의심스러운 고점 거부" 텔레그램 — <b>하루 최대 1회</b> (결함 6, 2026-10-11).
 *
 * <p>잔고 대조 불일치와 기존 상한 거부(isImplausible)가 같은 하루 몫을 쓴다 — 둘 다 "잔고 숫자가 튀어 최고 기록으로
 * 인정하지 않았다"는 같은 사건이라 사람에게는 한 통이면 된다. 계속되는지는 로그(10분에 1줄)에서 본다.
 *
 * <p>하루 몫은 <b>장중에 실제로 내보낼 때만</b> 쓴다. {@code TelegramNotifier}는 장 밖이면 조용히 버리므로, 장 밖에서
 * 도장을 먼저 찍으면 그날 경보가 사라진다(전고점 경신 알림의 감사 M-1과 같은 교훈). paper에서 전고점 감시는 장중에만 돌아
 * ({@code ShadowPortfolioTicker}) 장 밖 호출은 원래 없다 — 이 검사는 그 전제가 깨질 때의 안전장치다.
 *
 * <p>전송은 받은 {@code sender}(감시 스레드 밖 전송 대기열)로 넘긴다. 스프링 빈이 아니다 —
 * {@code EvidenceBasedPeakEquityCalibrator}(paper 전용)가 직접 만든다.
 */
final class SuspiciousPeakAlerter {

    private final Consumer<String> sender;
    private final Clock clock;
    private final MarketCalendarService marketCalendar;

    /** 마지막으로 보낸 날(KST) — 메모리로 충분하다(재기동하면 그날 한 번 더 갈 수 있다, 전고점 경신 알림과 같은 결정) */
    private LocalDate lastAlertDate;

    SuspiciousPeakAlerter(Consumer<String> sender, Clock clock, MarketCalendarService marketCalendar) {
        this.sender = sender;
        this.clock = clock;
        this.marketCalendar = marketCalendar;
    }

    synchronized void alert(SuspiciousPeakReason reason, double rejectedEquity, double currentPeak,
                            EquityCrossCheck check) {
        if (!marketCalendar.isDuringMarketHoursNow()) return;
        LocalDate today = LocalDate.now(clock);
        if (today.equals(lastAlertDate)) return;
        lastAlertDate = today;
        sender.accept(message(reason, rejectedEquity, currentPeak, check));
    }

    /** 사용자는 비전문가다 — 쉬운 말로, 숫자는 원 단위 쉼표 */
    private static String message(SuspiciousPeakReason reason, double rejectedEquity, double currentPeak,
                                  EquityCrossCheck check) {
        String what = reason == SuspiciousPeakReason.BALANCE_MISMATCH
                ? String.format("잔고 숫자가 서로 맞지 않아 최고 기록으로 인정하지 않았습니다 — 원래 숫자는 로그에 남겼습니다.%n"
                        + "증권사가 알려준 총자산 %,.0f원 / 직접 계산한 값(현금 + 보유 주식 × 현재가) %,.0f원 (차이 %+.2f%%)",
                        rejectedEquity, check.computedTotal(), check.diffRatio() * 100)
                : String.format("증권사가 알려준 총자산 %,.0f원이 지금까지의 기록으로 보아 너무 커서 "
                        + "최고 기록으로 인정하지 않았습니다 — 잔고 숫자가 튄 것일 수 있습니다.", rejectedEquity);
        return String.format("⚠️ [최고 기록 거부] %s%n"
                        + "지금 최고 기록 %,.0f원은 그대로 둡니다(강제정지 기준도 그대로).%n"
                        + "오늘은 이 알림을 더 보내지 않습니다 — 계속되는지는 로그에서 확인하세요.",
                what, currentPeak);
    }
}
