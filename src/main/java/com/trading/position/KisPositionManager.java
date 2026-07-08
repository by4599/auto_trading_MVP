package com.trading.position;

import jakarta.annotation.PostConstruct;
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
 * TODO Sprint 3 (Gate 3): consecutiveLossCount — 매도 체결(출구 전략) 구현 후
 * order_history의 실현손익 연속 카운트로 연동.
 */
@Service
@Profile("paper")
public class KisPositionManager implements PositionManager {

    private static final Logger log = LoggerFactory.getLogger(KisPositionManager.class);

    private static final long CACHE_TTL_MILLIS = 3_000L;

    private final BalanceClient balanceClient;
    private final PositionRepository positionRepository;
    private final DailyEquityRepository dailyEquityRepository;

    private final AtomicReference<CachedSnapshot> cache = new AtomicReference<>();

    public KisPositionManager(BalanceClient balanceClient,
                              PositionRepository positionRepository,
                              DailyEquityRepository dailyEquityRepository) {
        this.balanceClient = balanceClient;
        this.positionRepository = positionRepository;
        this.dailyEquityRepository = dailyEquityRepository;
    }

    @PostConstruct
    void warnInactiveRules() {
        log.warn("[운영 주의] ConsecutiveLossRule 비활성 상태 — 출구 전략(Gate 3) 구현 전까지 연속 손실 카운터는 0 고정입니다");
    }

    @Override
    public Account snapshotAccount() {
        CachedSnapshot cached = cache.get();
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
                return cached.account();
            }
            log.warn("잔고 API 실패 + 캐시 없음 — Position 테이블 폴백 (dailyPnl=0): {}", e.getMessage());
            return fallbackFromDb();
        }
    }

    // ── KIS 잔고 기반 스냅샷 ──────────────────────────────────────────────────

    private Account fetchFromKis() {
        BalanceClient.BalanceSnapshot balance = balanceClient.fetchBalance();

        List<Account.PositionSnapshot> snapshots = balance.holdings().stream()
                .map(h -> new Account.PositionSnapshot(
                        h.stockCode(), h.quantity(), h.averagePrice(), h.currentPrice()))
                .toList();

        double totalAssetValue = balance.totalAssetValue();
        double dailyPnlPercent = computeDailyPnl(totalAssetValue);

        log.debug("계좌 스냅샷(KIS): 총자산={} 일일손익={}% 보유종목={}",
                totalAssetValue, String.format("%.2f", dailyPnlPercent * 100), snapshots.size());

        return new Account(totalAssetValue, dailyPnlPercent, 0, snapshots);
    }

    /**
     * 당일 첫 스냅샷의 총자산을 daily_equity에 기록하고, 이후 그 기준값 대비
     * 등락률을 반환한다. 날짜 키가 바뀌면 자동으로 새 기준이 잡힌다 (= 일일 리셋).
     */
    private double computeDailyPnl(double currentEquity) {
        if (currentEquity <= 0) return 0.0;

        LocalDate today = LocalDate.now();
        DailyEquity start = dailyEquityRepository.findById(today).orElseGet(() -> {
            DailyEquity created = dailyEquityRepository.save(DailyEquity.of(today, currentEquity));
            log.info("[DailyEquity] 당일 시작 자산 기록: {} = {}", today, currentEquity);
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

        return new Account(totalAssetValue, 0.0, 0, snapshots);
    }

    // ── 내부 타입 ─────────────────────────────────────────────────────────────

    private record CachedSnapshot(Account account, Instant fetchedAt) {
        long ageMillis() {
            return Instant.now().toEpochMilli() - fetchedAt.toEpochMilli();
        }
    }
}
