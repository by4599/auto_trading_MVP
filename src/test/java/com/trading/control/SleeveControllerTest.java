package com.trading.control;

import com.trading.NotificationService;
import com.trading.bucket.BucketProperties;
import com.trading.bucket.InMemoryPortfolioState;
import com.trading.bucket.SleeveDrawdownProperties;
import com.trading.bucket.SleeveEquityCalculator;
import com.trading.bucket.SleeveRealized;
import com.trading.bucket.SleeveRealizedLedger;
import com.trading.bucket.SleeveRealizedSource;
import com.trading.bucket.SleeveStateStore;
import com.trading.bucket.StrategyBucket;
import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionManager;
import com.trading.position.PositionRepository;
import com.trading.risk.SleeveDrawdownGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 칸 낙폭 상한 조회·해제 API — 해제는 사람만(사유 필수), 해제 시 최고 기록을 지금 칸 자산으로 다시 잡는다.
 * 리포지토리·잔고(인터페이스)만 목, 나머지는 실객체. 날짜 사실관계: 2026-10-14 수.
 */
@DisplayName("SleeveController — 칸 상태 조회와 사람만 하는 잠금 해제")
class SleeveControllerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Map<String, Double> state = new HashMap<>();
    private final List<Position> held = new ArrayList<>();
    private final PositionRepository positionRepository = mock(PositionRepository.class);
    private final PositionManager positionManager = mock(PositionManager.class);
    private final NotificationService notifier = mock(NotificationService.class);
    private final CountingSource source = new CountingSource();
    private SleeveStateStore store;
    private SleeveController sut;

    /** 실현손익 원천 대역 — "다음 호출은 새로 읽어라" 요청과 그 뒤의 계산 호출을 센다 */
    private static final class CountingSource implements SleeveRealizedSource {
        int refreshRequests;
        int callsAfterRefresh;

        @Override
        public SleeveRealized realized(StrategyBucket bucket, LocalDate from, LocalDate toExclusive) {
            if (refreshRequests > 0) callsAfterRefresh++;
            return SleeveRealized.none();
        }

        @Override
        public void refreshOnNextCall() {
            refreshRequests++;
        }
    }

    @BeforeEach
    void setUp() {
        when(positionRepository.findAll()).thenAnswer(i -> List.copyOf(held));
        when(positionManager.snapshotAccount()).thenReturn(new Account(10_000_000, 0.0, 0, List.of()));
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 10, 14, 10, 0).atZone(KST).toInstant(), KST);
        BucketProperties buckets = new BucketProperties(true, "2026-07-20",
                10_000_000, 10_000_000, 10_000_000, 4_000_000, false, false, true);
        SleeveDrawdownProperties limits = new SleeveDrawdownProperties(0.12, 0.20);
        store = new SleeveStateStore(InMemoryPortfolioState.create(state));
        SleeveRealizedLedger ledger = new SleeveRealizedLedger(source, store, buckets, clock);
        SleeveEquityCalculator calculator = new SleeveEquityCalculator(buckets, positionRepository, ledger, clock);
        SleeveDrawdownGuard guard = new SleeveDrawdownGuard(store, limits, notifier, clock);
        sut = new SleeveController(buckets, limits, store, calculator, guard, positionManager, source);
    }

    private void lockTrend() {
        store.lock(StrategyBucket.TREND,
                new SleeveStateStore.LockState(true, Instant.EPOCH, 0.125, 3_500_000, 0.12));
    }

    private void holdTrend() {
        Position p = Position.empty("005930");
        p.assignBucketIfAbsent(StrategyBucket.TREND);
        p.applyBuy(100, 40_000);
        held.add(p);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> status, StrategyBucket bucket) {
        return ((List<Map<String, Object>>) status.get("sleeves")).stream()
                .filter(r -> bucket.name().equals(r.get("bucket")))
                .findFirst().orElseThrow();
    }

    // ── 조회 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("칸마다 자산·최고 기록·낙폭·한도·잠김·측정 불가 개수를 보여준다")
    void status_shows_each_sleeve() {
        holdTrend();
        when(positionManager.snapshotAccount()).thenReturn(new Account(9_000_000, 0.0, 0, List.of(
                new Account.PositionSnapshot("005930", 100, 40_000, 39_000))));

        Map<String, Object> status = sut.status();
        Map<String, Object> trend = row(status, StrategyBucket.TREND);

        assertThat(status.get("estimated")).isEqualTo(true);
        assertThat(trend.get("active")).isEqualTo(true);
        assertThat(trend.get("equity")).isEqualTo(3_900_000L);
        assertThat(trend.get("peak")).isEqualTo(4_000_000L);
        assertThat(trend.get("drawdownPercent")).isEqualTo(2.5);
        assertThat(trend.get("limitPercent")).isEqualTo(12.0);
        assertThat(trend.get("locked")).isEqualTo(false);
        assertThat(trend.get("unmeasurableTrades")).isEqualTo(0);
        assertThat(row(status, StrategyBucket.EVENT).get("active")).isEqualTo(false);
    }

    @Test
    @DisplayName("잔고가 낡아 계산할 수 없으면 숫자를 꾸미지 않고 보류 사유를 보여준다")
    void status_shows_deferral_reason() {
        holdTrend();
        when(positionManager.snapshotAccount()).thenReturn(new Account(9_000_000, 0.0, 0, List.of(
                new Account.PositionSnapshot("005930", 100, 40_000, 39_000))).asStale());

        Map<String, Object> trend = row(sut.status(), StrategyBucket.TREND);

        assertThat(trend.get("equity")).isNull();
        assertThat((String) trend.get("deferredReason")).contains("낡");
    }

    // ── 해제 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("사유가 없으면 해제하지 않는다")
    void unlock_requires_reason() {
        lockTrend();

        Map<String, Object> r = sut.unlock("TREND", Map.of("reason", "   "));

        assertThat(r.get("success")).isEqualTo(false);
        assertThat(store.isLocked(StrategyBucket.TREND)).isTrue();
    }

    @Test
    @DisplayName("사유가 너무 길면(200자 초과) 해제하지 않는다")
    void unlock_rejects_overlong_reason() {
        lockTrend();

        Map<String, Object> r = sut.unlock("TREND", Map.of("reason", "가".repeat(201)));

        assertThat(r.get("success")).isEqualTo(false);
        assertThat(store.isLocked(StrategyBucket.TREND)).isTrue();
    }

    @Test
    @DisplayName("모르는 칸 이름이면 해제하지 않는다")
    void unlock_rejects_unknown_bucket() {
        Map<String, Object> r = sut.unlock("ABC", Map.of("reason", "확인"));

        assertThat(r.get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("잠기지 않은 칸은 해제할 것이 없다고 답한다")
    void unlock_of_unlocked_sleeve_fails() {
        Map<String, Object> r = sut.unlock("TREND", Map.of("reason", "확인"));

        assertThat(r.get("success")).isEqualTo(false);
        verify(notifier, never()).sendCritical(anyString());
    }

    @Test
    @DisplayName("해제하면 잠금이 풀리고 최고 기록이 지금 칸 자산으로 다시 잡히며 텔레그램이 간다")
    void unlock_succeeds_and_resets_peak() {
        lockTrend();

        Map<String, Object> r = sut.unlock("trend", Map.of("reason", "손실 원인 확인 — 재가동"));

        assertThat(r.get("success")).isEqualTo(true);
        assertThat(r.get("newPeak")).isEqualTo(4_000_000L);
        assertThat(store.isLocked(StrategyBucket.TREND)).isFalse();
        assertThat(store.peak(StrategyBucket.TREND).orElseThrow().value()).isEqualTo(4_000_000);
        verify(notifier).sendCritical(contains("손실 원인 확인 — 재가동"));
    }

    @Test
    @DisplayName("보유가 남았는데 잔고가 낡아 칸 자산을 모르면 해제하지 않는다 — 틀린 최고 기록을 박지 않게")
    void unlock_refused_when_equity_cannot_be_computed() {
        lockTrend();
        holdTrend();
        when(positionManager.snapshotAccount()).thenReturn(new Account(9_000_000, 0.0, 0, List.of(
                new Account.PositionSnapshot("005930", 100, 40_000, 39_000))).asStale());

        Map<String, Object> r = sut.unlock("TREND", Map.of("reason", "확인"));

        assertThat(r.get("success")).isEqualTo(false);
        assertThat(store.isLocked(StrategyBucket.TREND)).isTrue();
    }

    @Test
    @DisplayName("해제는 계산 전에 '새로 읽기'를 요청한다 — 해제 직전 반영된 체결이 새 최고 기록에서 빠지지 않게 (46b N-2)")
    void unlock_requests_fresh_data_before_computing() {
        lockTrend();

        Map<String, Object> r = sut.unlock("TREND", Map.of("reason", "확인"));

        assertThat(r.get("success")).isEqualTo(true);
        assertThat(source.refreshRequests).isEqualTo(1);
        assertThat(source.callsAfterRefresh).isPositive();   // 새로 읽기 요청 뒤에 칸 자산을 계산했다
    }

    @Test
    @DisplayName("상태 조회는 새로 읽기를 요청하지 않는다 — 1분 감시의 회차 재사용 동작을 건드리지 않는다")
    void status_does_not_request_refresh() {
        sut.status();

        assertThat(source.refreshRequests).isZero();
    }
}
