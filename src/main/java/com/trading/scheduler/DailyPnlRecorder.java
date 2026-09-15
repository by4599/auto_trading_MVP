package com.trading.scheduler;

import com.trading.NotificationService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

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
 *
 * <p>알림은 <b>실제로 보낸 것만</b> 원장에 찍는다(notifiedAt). 텔레그램은 거래일 09:00~15:30에만
 * 나가는데 스케줄 스레드가 1개라 <b>할 일이 많은 날일수록 15:29 작업이 밀린다</b>(실측 6일 중 1일은
 * 15:30을 넘겼다). 그래서 창을 넘겨 못 보낸 날은 조용히 버리지 않고 다음 거래일 <b>09:05</b>에
 * 이월 발송한다.
 */
@Component
@Profile("paper")
public class DailyPnlRecorder {

    private static final Logger log = LoggerFactory.getLogger(DailyPnlRecorder.class);

    /** 아침 이월로 한 번에 보내는 최대 건수 — 오래 꺼져 있었다고 무더기로 쏟아지지 않게 */
    private static final int MAX_CATCHUP = 5;

    private final BalanceClient balanceClient;
    private final DailyEquityRepository dailyEquityRepository;
    private final MarketCalendarService marketCalendar;
    private final KisProperties kisProperties;
    private final NotificationService notifier;
    private final Clock clock;

    public DailyPnlRecorder(BalanceClient balanceClient,
                            DailyEquityRepository dailyEquityRepository,
                            MarketCalendarService marketCalendar,
                            KisProperties kisProperties,
                            NotificationService notifier,
                            Clock clock) {
        this.balanceClient = balanceClient;
        this.dailyEquityRepository = dailyEquityRepository;
        this.marketCalendar = marketCalendar;
        this.kisProperties = kisProperties;
        this.notifier = notifier;
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

    /**
     * 평일 09:05 KST — 못 보낸 마감 손익을 다음 거래일 아침에 이월 발송한다.
     *
     * <p>왜 아침인가: 전송 창(거래일 09:00~15:30)이 막 열린 직후라 여유가 크고, 스케줄 스레드
     * 1개를 {@code @Scheduled} 14개가 나눠 쓰는 이 앱에서 <b>마감 직전보다 경합이 훨씬 낮다</b>.
     * 마감 직전에는 타임컷·재시도 스윕·1초 감시가 같은 스레드에 몰려 크론이 밀린다
     * (실측: 15:25 크론이 +321초 밀려 15:30:21에 발화한 날이 있다).
     *
     * <p>여기서 보내는 것은 <b>알림뿐</b>이다 — 잔고를 다시 부르지 않는다. 원장은 이미
     * 그날 장중에 찍힌 값이 정본이고, 아침에 다시 읽으면 다른 날의 자산이 섞인다.
     */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Seoul")
    public void sendPendingNotifications() {
        sendPendingFor(LocalDate.now(clock));
    }

    void sendPendingFor(LocalDate today) {
        if (!kisProperties.isConfigured()) return;
        if (!marketCalendar.isTradingDay(today)) {
            log.debug("[일별손익] {} 휴장일 — 이월 발송 스킵", today);
            return;
        }
        if (!marketCalendar.isDuringMarketHoursNow()) {
            log.info("[일별손익] {} 전송 창 밖 — 이월 발송을 다음 기회로 미룬다", today);
            return;
        }

        List<DailyEquity> pending = dailyEquityRepository
                .findByEndEquityNotNullAndNotifiedAtNullOrderByTradeDateAsc().stream()
                .filter(DailyEquity::isClosed)                   // 마감값이 있어야 보낼 손익이 있다
                .filter(d -> d.getTradeDate().isBefore(today))   // 오늘 것은 그날 15:29 경로가 맡는다
                .toList();
        if (pending.isEmpty()) return;

        List<DailyEquity> recent = trimToRecent(pending);
        log.info("[일별손익] 못 보낸 마감 알림 {}건 이월 발송", recent.size());
        for (DailyEquity ledger : recent) {
            sendAndMark(ledger, closeMessage(ledger, true));
        }
    }

    /**
     * 최근 {@value #MAX_CATCHUP}건만 남긴다 (오름차순 유지 — 오래된 날짜부터 보낸다).
     * 잘라낸 날은 notifiedAt이 비어 있으므로 사라지지 않고 다음 아침에 이어서 나간다.
     */
    private List<DailyEquity> trimToRecent(List<DailyEquity> pending) {
        if (pending.size() <= MAX_CATCHUP) return pending;
        int cut = pending.size() - MAX_CATCHUP;
        log.warn("[일별손익] 밀린 마감 알림 {}건 중 최근 {}건만 보낸다 — 이번에 넘기는 날: {}",
                pending.size(), MAX_CATCHUP,
                pending.subList(0, cut).stream()
                        .map(d -> d.getTradeDate().toString())
                        .collect(Collectors.joining(", ")));
        return pending.subList(cut, pending.size());
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

        notifyClose(ledger);
    }

    /** 원장이 저장된 뒤에만 부른다 — 알림은 언제나 마지막이고, 실패해도 원장을 되돌리지 않는다. */
    private void notifyClose(DailyEquity ledger) {
        if (ledger.getNetPnl() == null) return;
        sendAndMark(ledger, closeMessage(ledger, false));
    }

    /**
     * 보내기 직전에 전송 창(거래일 09:00~15:30)을 <b>호출 측에서 한 번 더</b> 본다.
     *
     * <p>TelegramNotifier 안에도 같은 판정이 있지만, 창 밖이면 <b>조용히 건너뛰고</b>(log.info)
     * 호출 측에 아무것도 알려주지 않는다 — 보냈는지를 모르면 이월할 수도, 경고할 수도 없다.
     * 게다가 recordCloseFor의 첫 게이트와 전송 사이에는 잔고 조회(레이트리밋 ≥1초 + HTTP 최대
     * 10초)가 끼어 있어 그 사이에 창이 닫힐 수 있다. 중복 판정은 <b>의도된 것</b>이다.
     *
     * <p>notifiedAt은 실제로 보낸 뒤에만 찍는다 — 못 보낸 날은 비워 둬야 아침에 이월된다.
     * ⚠ 다만 그 값의 뜻은 "전달됐다"가 아니라 <b>"창 안에서 예외 없이 send()를 불렀다"</b>이다.
     * {@code TelegramNotifier.send}가 HTTP 실패를 안에서 삼키므로 서버가 거부해도 도장은 찍힌다
     * — 이월 장치는 <b>창 이탈만</b> 복구할 수 있고 전달 실패는 복구하지 못한다.
     *
     * <p>도장 저장까지 try 안에 둔다 — 여기서 예외가 새면 이월 루프가 남은 건을 버리고
     * 스케줄 메서드 밖으로 던진다. 알림은 곁다리이므로 어떤 실패도 위로 올리지 않는다.
     */
    private void sendAndMark(DailyEquity ledger, String message) {
        LocalDate date = ledger.getTradeDate();
        if (!marketCalendar.isDuringMarketHoursNow()) {
            log.warn("[일별손익] {} 전송 창(거래일 09:00~15:30)을 넘겨 알림 보류 — "
                    + "다음 거래일 아침에 보낸다", date);
            return;
        }
        try {
            notifier.sendCritical(message);
            ledger.markNotified(LocalDateTime.now(clock));
            dailyEquityRepository.save(ledger);
        } catch (Exception e) {
            log.warn("[일별손익] {} 알림 처리 실패 — 다음 거래일 아침에 다시 시도한다: {}",
                    date, e.getMessage());
        }
    }

    /** 당일 발송과 이월 발송이 같은 문장을 쓰되 머리말만 다르다 — 이월분은 어느 날 것인지 밝힌다 */
    private String closeMessage(DailyEquity ledger, boolean carriedOver) {
        double netPnl = ledger.getNetPnl();
        double startEquity = ledger.getStartEquity();
        String verdict = netPnl > 0 ? "벌었습니다" : netPnl < 0 ? "잃었습니다" : "본전입니다";
        double percent = startEquity > 0 ? netPnl / startEquity * 100 : 0.0;
        String head = carriedOver
                ? String.format("📒 [지난 거래일(%s) 마감분]", ledger.getTradeDate())
                : String.format("📒 [%s 마감]", ledger.getTradeDate());
        return String.format("%s %s %s원 %s (%.2f%%)%n"
                        + "총자산 %s원 → %s원%n"
                        + "수수료·세금이 이미 빠진 실제 금액입니다.",
                head, carriedOver ? "그날" : "오늘",
                won(Math.abs(netPnl)), verdict, percent,
                won(startEquity), won(ledger.getEndEquity()));
    }

    private static String won(Double value) {
        return value == null ? "-" : String.format("%,.0f", value);
    }
}
