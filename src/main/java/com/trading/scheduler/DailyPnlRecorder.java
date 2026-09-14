package com.trading.scheduler;

import com.trading.market.KisProperties;
import com.trading.market.MarketCalendarService;
import com.trading.position.BalanceClient;
import com.trading.position.DailyEquity;
import com.trading.position.DailyEquityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 일별 순손익 원장의 마감 기록 장치 (측정 전용 — 매매 경로 무변경).
 *
 * <p>하루의 시작 자산은 이미 {@code KisPositionManager}가 그날 첫 잔고 스냅샷에서
 * daily_equity에 찍는다. 이 스케줄러는 <b>장이 끝난 뒤 같은 행에 마감 자산·예수금을
 * 한 번 더 찍어</b> 그 차이를 그날 순손익으로 남긴다.
 *
 * <p>왜 필요한가: 모의 체결조회(VTTC8001R)가 매도에 빈 응답을 주는 결함(CLAUDE.md 결함 5)
 * 때문에 거래 단위 실현손익은 측정이 막혀 있다. 그런데 <b>예수금(현금)은 증권사가 이미
 * 보내주고 있다</b> — 수수료·세금이 이미 빠진 값이다. 거래 단위는 못 갈라도
 * "하루 동안 실제로 얼마가 늘고 줄었나"는 이 두 시점 비교로 정확히 알 수 있다.
 *
 * <p>거래 단위 귀속은 하지 않는다 — 현금 증가분을 특정 매도에 붙이려면 그 사이 다른 체결이
 * 없어야 하는데 보장할 수 없다.
 *
 * <p>15:29 KST에 돈다 — 타임컷(15:15)·최대보유(15:17)·재시도 스윕(최종 15:28)이 끝났고
 * 장 마감(15:30) 직전이라 <b>아직 장중</b>이다. 그 시각에 앱이 꺼져 있던 날은 마감이 비어
 * 있게 되고 (잔고는 소급 조회가 안 된다), 원장은 그 날을 "미마감"으로 남긴다 — 추측해 채우지 않는다.
 */
@Component
@Profile("paper")
public class DailyPnlRecorder {

    private static final Logger log = LoggerFactory.getLogger(DailyPnlRecorder.class);

    private final BalanceClient balanceClient;
    private final DailyEquityRepository dailyEquityRepository;
    private final MarketCalendarService marketCalendar;
    private final KisProperties kisProperties;
    private final Clock clock;

    public DailyPnlRecorder(BalanceClient balanceClient,
                            DailyEquityRepository dailyEquityRepository,
                            MarketCalendarService marketCalendar,
                            KisProperties kisProperties,
                            Clock clock) {
        this.balanceClient = balanceClient;
        this.dailyEquityRepository = dailyEquityRepository;
        this.marketCalendar = marketCalendar;
        this.kisProperties = kisProperties;
        this.clock = clock;
    }

    /**
     * 평일 15:29 KST — 타임컷 최종스윕(15:28)과 장 마감(15:30) 사이.
     *
     * 마감 후(15:40 등)에 두면 잔고 API를 장외에 부르게 되고, 그 실패가 KisApiClient의 연속
     * 실패 카운터에 쌓여 다음 아침을 SAFE_MODE로 시작시킨다 — 미러가 09:00~15:30으로 창을
     * 좁힌 것과 같은 이유다. 15:28 매도가 아직 체결 전이면 현금·보유 구분은 흔들리지만
     * 총자산은 둘을 합친 값이라 순손익은 영향받지 않는다.
     */
    @Scheduled(cron = "0 29 15 * * MON-FRI", zone = "Asia/Seoul")
    public void recordClose() {
        recordCloseFor(LocalDate.now(clock));
    }

    // @Transactional을 붙이지 않는다 — 같은 빈 안에서 부르면 프록시를 안 타 무효다.
    // findById로 받은 detached 엔티티를 고쳐 save()로 명시 병합하므로 트랜잭션 없이도 성립한다.
    void recordCloseFor(LocalDate today) {
        if (!kisProperties.isConfigured()) return;
        if (!marketCalendar.isTradingDay(today)) {
            log.debug("[일별손익] {} 휴장일 — 마감 기록 스킵", today);
            return;
        }
        if (!marketCalendar.isDuringMarketHoursNow()) {
            log.warn("[일별손익] {} 장중이 아니어서 마감 기록 보류 — 장외 잔고 호출은 하지 않는다", today);
            return;
        }

        DailyEquity ledger = dailyEquityRepository.findById(today).orElse(null);
        if (ledger == null) {
            log.warn("[일별손익] {} 시작 자산 기록이 없어 순손익을 낼 수 없습니다 "
                    + "(그날 잔고를 한 번도 못 읽었다는 뜻)", today);
            return;
        }
        if (ledger.isClosed()) {
            return;  // 같은 날 중복 기록 방지
        }

        BalanceClient.BalanceSnapshot balance;
        try {
            balance = balanceClient.fetchBalance();
        } catch (Exception e) {
            log.warn("[일별손익] {} 잔고 조회 실패 — 마감 기록 보류: {}", today, e.getMessage());
            return;
        }
        if (balance.totalAssetValue() <= 0) {
            log.warn("[일별손익] {} 총자산이 0 이하 — 마감 기록 보류 (잘못된 값으로 원장을 더럽히지 않는다)",
                    today);
            return;
        }

        ledger.recordClose(balance.totalAssetValue(), balance.deposit());
        dailyEquityRepository.save(ledger);

        log.info("[일별손익] {} 마감 — 총자산 {}원 → {}원 (순손익 {}원) · 예수금 {}원 → {}원 (현금 {}원)",
                today,
                won(ledger.getStartEquity()), won(ledger.getEndEquity()), won(ledger.getNetPnl()),
                won(ledger.getStartDeposit()), won(ledger.getEndDeposit()), won(ledger.getCashDelta()));
    }

    private static String won(Double value) {
        return value == null ? "-" : String.format("%,.0f", value);
    }
}
