package com.trading.position;

import com.trading.market.MarketCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PositionManager 모의투자 구현체.
 *
 * KIS 잔고조회 API로 "예수금 포함 총자산 + 실시간 현재가" 스냅샷을 만든다 (감사 F-1 해소).
 * dailyPnlPercent = (현재 총자산 - 당일 시작 자산) / 당일 시작 자산 — 평가손익 포함 (감사 F-5).
 * 당일 시작 자산은 daily_equity 테이블에 영속화되어 장중 재시작에도 기준이 유지된다.
 *
 * 레이트리밋 대응: 스냅샷은 3초 TTL 캐시. 소비자(TradingScheduler, ShadowPortfolio,
 * RiskMonitor)가 초당 수 회 호출해도 잔고 API 실호출은 3초당 1회다.
 *
 * 폴백: API 실패 시 마지막 성공 스냅샷(최대 캐시 나이 무제한, 경고 로그),
 * 성공 이력이 없으면 Position 테이블 기반 폴백(dailyPnl=0, totalAssetValue는
 * 평단가 근사 — RiskMonitor·GlobalEquityStopRule은 totalAssetValue<=0 가드로 오탐 방지).
 *
 * 장외 스킵: 장이 닫혀 있고 보유 종목이 0이면 잔고 API를 아예 부르지 않고 마지막 스냅샷을
 * 낡은 값으로 돌려준다 (canSkipBalanceCall — 근거와 안전성 논증은 그 메서드 주석).
 *
 * consecutiveLossCount는 TradeResultTracker(portfolio_state 영속화)에서 읽는다 (F-5 해소).
 */
@Service
@Profile("paper")
public class KisPositionManager implements PositionManager {

    private static final Logger log = LoggerFactory.getLogger(KisPositionManager.class);

    private static final long CACHE_TTL_MILLIS = 3_000L;

    private final BalanceClient balanceClient;
    private final PositionRepository positionRepository;
    private final DailyEquityRepository dailyEquityRepository;
    private final TradeResultTracker tradeResultTracker;
    private final MarketCalendarService marketCalendar;

    private final AtomicReference<CachedSnapshot> cache = new AtomicReference<>();

    public KisPositionManager(BalanceClient balanceClient,
                              PositionRepository positionRepository,
                              DailyEquityRepository dailyEquityRepository,
                              TradeResultTracker tradeResultTracker,
                              MarketCalendarService marketCalendar) {
        this.balanceClient = balanceClient;
        this.positionRepository = positionRepository;
        this.dailyEquityRepository = dailyEquityRepository;
        this.tradeResultTracker = tradeResultTracker;
        this.marketCalendar = marketCalendar;
    }

    @Override
    public Account snapshotAccount() {
        CachedSnapshot cached = cache.get();
        if (canSkipBalanceCall(cached)) {
            log.debug("[KisPositionManager] 장외 + 보유 0 — 잔고 API를 부르지 않고 마지막 스냅샷을 낡은 값으로 돌려준다");
            return cached != null ? cached.account().asStale() : fallbackFromDb();
        }
        if (cached != null && cached.ageMillis() < CACHE_TTL_MILLIS) {
            return cached.account();
        }

        try {
            Account fresh = fetchFromKis();
            cache.set(new CachedSnapshot(fresh, Instant.now()));
            return fresh;
        } catch (Exception e) {
            if (cached != null) {
                log.warn("잔고 API 실패 — {}초 전 스냅샷으로 대체: {}",
                        cached.ageMillis() / 1000, e.getMessage());
                return cached.account().asStale();  // 낡은 값 — 청산 판정에서 걸러지도록 표시
            }
            log.warn("잔고 API 실패 + 캐시 없음 — Position 테이블 폴백 (dailyPnl=0): {}", e.getMessage());
            return fallbackFromDb();
        }
    }

    // ── 장외 잔고 폴링 중단 ───────────────────────────────────────────────────

    /**
     * 장이 닫혀 있고 보유 종목이 없으면 잔고 API를 부르지 않는다 (2026-09-30).
     *
     * <p>왜 필요한가: 이 스냅샷은 {@code ShadowPortfolio.tick}·{@code RiskMonitor}·
     * {@code StopLossMonitor}가 24시간 1초마다 부른다. 장외에는 매매가 없으니 순수 낭비인데,
     * 그 실패가 {@code KisApiClient}의 연속 실패 카운터에 쌓여 <b>아침을 SAFE_MODE 근처에서
     * 시작시킨다</b>(2026-09-22 16시 한 시간에 잔고 실패 135건).
     *
     * <p>왜 안전한가 — 판정 동작이 바뀌지 않는다:
     * <ul>
     *   <li>지금도 장외 호출은 대부분 실패해 낡은 스냅샷 폴백으로 끝난다. 그리고 낡은
     *       스냅샷이면 {@code RiskMonitor}(청산)·{@code StopLossMonitor}(손절)는 판정을
     *       건너뛰고 {@code ShadowPortfolio.tick}은 전고점을 갱신하지 않는다
     *       ({@link Account#isFresh()} 데이터 품질 게이트, 2026-08). 즉 <b>지금 장외에 우연히
     *       일어나는 일(실패 → 낡음 → 스킵)을 호출 없이 결정론적으로 만드는 것</b>이다.</li>
     *   <li>매수 차단 룰은 낡은 값이면 보수적으로 막는 쪽이라 영향이 없다. 게다가 장외에는
     *       {@code TradingScheduler}가 아예 돌지 않아 신호 판정 자체가 없다.</li>
     * </ul>
     *
     * <p>보유가 있으면 장외에도 계속 부른다 — 지금 paper는 15:15 타임컷(Gate 3)으로 밤에 보유가
     * 없지만, 다일 보유 칸이 켜지면 밤샘 감시(평가액·손절선)가 필요하다.
     */
    private boolean canSkipBalanceCall(CachedSnapshot cached) {
        if (marketCalendar.isDuringMarketHoursNow()) return false;
        return !hasHoldings(cached);
    }

    /** 보유 판단은 보수적으로 — 브로커가 마지막으로 알려준 보유분과 우리 DB 중 하나라도 있으면 "있다". */
    private boolean hasHoldings(CachedSnapshot cached) {
        if (cached != null && cached.account().getPositionCount() > 0) return true;
        return positionRepository.count() > 0;
    }

    // ── KIS 잔고 기반 스냅샷 ──────────────────────────────────────────────────

    private Account fetchFromKis() {
        BalanceClient.BalanceSnapshot balance = balanceClient.fetchBalance();

        List<Account.PositionSnapshot> snapshots = balance.holdings().stream()
                .map(h -> new Account.PositionSnapshot(
                        h.stockCode(), h.quantity(), h.averagePrice(), h.currentPrice()))
                .toList();

        double totalAssetValue = balance.totalAssetValue();
        double dailyPnlPercent = computeDailyPnl(totalAssetValue, balance.deposit());

        log.debug("계좌 스냅샷(KIS): 총자산={} 일일손익={}% 보유종목={}",
                totalAssetValue, String.format("%.2f", dailyPnlPercent * 100), snapshots.size());

        // 총자산 대조 판정(결함 6)을 함께 싣는다 — 지금은 ShadowPortfolio의 전고점 인정 여부만 이 표시를 본다
        return new Account(totalAssetValue, dailyPnlPercent,
                tradeResultTracker.getConsecutiveLossCount(), snapshots)
                .withEquityCheck(balance.equityCheck());
    }

    /**
     * 당일 첫 스냅샷의 총자산·예수금을 daily_equity에 기록하고, 이후 그 기준값 대비
     * 등락률을 반환한다. 날짜 키가 바뀌면 자동으로 새 기준이 잡힌다 (= 일일 리셋).
     *
     * 예수금은 등락률 계산에 쓰이지 않는다 — 장 마감 후 값과 짝을 이뤄 그날 현금 증감을
     * 내기 위한 기록일 뿐이다 (DailyPnlRecorder).
     */
    private double computeDailyPnl(double currentEquity, double currentDeposit) {
        if (currentEquity <= 0) return 0.0;

        LocalDate today = LocalDate.now();
        DailyEquity start = dailyEquityRepository.findById(today).orElseGet(() -> {
            DailyEquity created = dailyEquityRepository.save(
                    DailyEquity.of(today, currentEquity, currentDeposit));
            log.info("[DailyEquity] 당일 시작 자산 기록: {} = {} (예수금 {})",
                    today, currentEquity, currentDeposit);
            return created;
        });

        if (start.getStartEquity() <= 0) return 0.0;
        return (currentEquity - start.getStartEquity()) / start.getStartEquity();
    }

    // ── DB 폴백 (기존 방식 — 잔고 API 성공 이력이 전혀 없을 때만) ─────────────

    private Account fallbackFromDb() {
        List<Position> positions = positionRepository.findAll();

        List<Account.PositionSnapshot> snapshots = positions.stream()
                .map(p -> new Account.PositionSnapshot(
                        p.getStockCode(),
                        p.getQuantity(),
                        p.getAveragePrice(),
                        p.getAveragePrice()  // 현재가 불명 — 평단가 근사
                ))
                .toList();

        double totalAssetValue = snapshots.stream()
                .mapToDouble(Account.PositionSnapshot::marketValue)
                .sum();

        return new Account(totalAssetValue, 0.0,
                tradeResultTracker.getConsecutiveLossCount(), snapshots).asStale();  // DB 폴백 — 낡음 표시
    }

    // ── 내부 타입 ─────────────────────────────────────────────────────────────

    private record CachedSnapshot(Account account, Instant fetchedAt) {
        long ageMillis() {
            return Instant.now().toEpochMilli() - fetchedAt.toEpochMilli();
        }
    }
}
