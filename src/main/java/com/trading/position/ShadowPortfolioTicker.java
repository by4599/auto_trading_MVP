package com.trading.position;

import com.trading.market.MarketCalendarService;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 모의투자 전고점 감시를 <b>장중에만</b> 돌린다 (감사 H-1(b), 2026-10-01).
 *
 * <p>다일 보유분이 15:30을 넘기면 KisPositionManager가 장외에도 잔고를 불러 "신선한" 스냅샷이 생긴다.
 * 전고점은 리셋이 없어 한 번 잘못 오르면 영구히 남는데, 관측된 오염 2건(09-11 17:17 등)은 둘 다 장 밖이었다
 * (CLAUDE.md 결함 6 — 그 결과 09-10~09-21 7거래일 매매 0건). 장 밖에는 가격이 움직이지 않으니 올릴 이유도 없다.
 * 장 시간 판정은 RiskMonitor와 같은 {@link MarketCalendarService#isDuringMarketHoursNow()}다.
 *
 * <p>가드를 {@link ShadowPortfolio#tick()} 안이 아니라 여기 둔 이유: 백테스트(BacktestRunner)가 봉마다
 * tick()을 직접 부른다. 백테스트에서는 스케줄링이 꺼져 있어(SchedulingConfig) 이 빈이 없어도 된다.
 */
@Component
@Profile("paper")
public class ShadowPortfolioTicker {

    private final ShadowPortfolio shadowPortfolio;
    private final MarketCalendarService marketCalendar;

    public ShadowPortfolioTicker(ShadowPortfolio shadowPortfolio, MarketCalendarService marketCalendar) {
        this.shadowPortfolio = shadowPortfolio;
        this.marketCalendar = marketCalendar;
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        if (!marketCalendar.isDuringMarketHoursNow()) return;
        shadowPortfolio.tick();
    }
}
