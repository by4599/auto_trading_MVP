package com.trading.risk;

import com.trading.bucket.SleeveEquity;

/**
 * 칸 낙폭 상한 텔레그램 문구 — 사용자는 코드 전문가가 아니므로 쉬운 말로 쓴다(CLAUDE.md 피드백 규칙).
 * 문구만 모아 둔 곳이라 판정 로직({@link SleeveDrawdownGuard})과 따로 둔다.
 */
final class SleeveDrawdownMessages {

    private SleeveDrawdownMessages() {}

    static String locked(SleeveEquity sleeve, double peak, double drawdown, double limit) {
        return String.format(
                "🔒 [칸 손실 한도] %s 칸이 잠겼습니다.%n"
                        + "이 칸의 돈이 최고 %,.0f원에서 지금 %,.0f원으로 %.1f%% 줄어, 한도 %.0f%%에 닿았습니다"
                        + " (1분 간격 2번 연속 확인).%n"
                        + "→ 이 칸만 새로 사는 것을 멈추고, 이 칸이 가진 주식을 팝니다. 다른 칸은 그대로 운영합니다.%n"
                        + "→ 다시 열려면 사람이 확인한 뒤 해제해야 합니다: POST /api/buckets/%s/unlock (사유 필수)%n"
                        + "※ 판 가격 일부는 추정치입니다(증권사 모의계좌가 매도 체결가를 주지 않음)."
                        + " 가격을 못 구한 거래 %d건은 0원으로 계산했습니다.",
                sleeve.bucket().getDisplayName(), peak, sleeve.equity(), drawdown * 100, limit * 100,
                sleeve.bucket().name(), sleeve.realized().unmeasurableCount());
    }

    static String unlocked(SleeveEquity current, double limit, String reason) {
        return String.format(
                "🔓 [칸 잠금 해제] %s 칸을 사람이 다시 열었습니다. 사유: %s%n"
                        + "최고 기록을 지금 칸 자산 %,.0f원으로 다시 잡았습니다 — 여기서부터 다시 %.0f%% 한도를 잽니다.",
                current.bucket().getDisplayName(), reason, current.equity(), limit * 100);
    }
}
