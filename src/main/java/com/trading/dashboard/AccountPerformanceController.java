package com.trading.dashboard;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 계좌 기준 성적 조회 API (읽기 전용, 모의투자 전용).
 *
 * GET /api/performance/account?days=90 — 총자산 시계열·일별 순손익·누적 수익률·낙폭·강제정지 문턱
 *
 * <p>기존 {@code GET /api/performance}(trade_result 기준)와 나란히 둔다. 그쪽은 모의 매도
 * 체결가 결함(CLAUDE.md 결함 5)으로 손익이 과대계상되므로 응답에 경고를 달아 두었고,
 * <b>금액의 정본은 이쪽</b>이다.
 *
 * <p>{@code @Profile("paper")}인 이유: 실계좌 원장과 {@code ShadowPortfolio} 전고점을 읽는
 * 운영 조회다. 백테스트에서 뜨면 시뮬레이션 원장을 실계좌 성적으로 오독할 여지가 생긴다
 * (같은 이유로 {@code DailyPnlController}도 paper 전용이다). 그래서 프로필 없는
 * {@code PerformanceController}에 메서드를 얹지 않고 별도 컨트롤러로 두었다 —
 * 얹었다면 backtest 프로필에서 빈이 없어 애플리케이션 기동이 깨진다.
 */
@RestController
@RequestMapping("/api/performance")
@Profile("paper")
public class AccountPerformanceController {

    private final AccountPerformanceService accountPerformanceService;

    public AccountPerformanceController(AccountPerformanceService accountPerformanceService) {
        this.accountPerformanceService = accountPerformanceService;
    }

    @GetMapping("/account")
    public Map<String, Object> getAccountPerformance(@RequestParam(defaultValue = "90") int days) {
        return accountPerformanceService.accountPerformance(days);
    }
}
