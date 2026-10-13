package com.trading.bucket;

import com.trading.position.Account;
import com.trading.position.Position;
import com.trading.position.PositionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 칸 자산 = 배분금 + 실현손익 + 보유 평가손익 (ADR-001 §2.2 슬리브별 낙폭 상한의 분자).
 *
 * <p><b>모르면 꾸미지 않고 보류한다.</b> 값 하나라도 확실하지 않으면 그 회차는 계산하지 않고 사유를
 * 돌려준다 — 옛 가격으로 계산하면 헛발동, 0으로 채우면 조용한 통과가 되기 때문이다.
 * <ul>
 *   <li>보유가 있는데 잔고가 낡았거나 없다 → 현재가를 모른다</li>
 *   <li>DB 보유가 증권사 잔고에 없거나 수량이 다르다 → 체결이 아직 장부에 안 들어온 중간 상태다</li>
 *   <li>오늘 판 거래의 매도가를 아직 모른다 → 분봉은 장 마감 뒤(15:40) 쌓인다. 0으로 넣으면 이긴 청산은
 *       가짜 낙폭, 진 청산은 손실 은폐가 된다. 그래서 청산이 있던 날은 그 뒤로 판정을 쉰다</li>
 * </ul>
 * 지난날의 측정 불가는 영영 못 구하는 값이라 보류하지 않고 0원으로 넣되 개수를 남긴다(사용자 승인 설계).
 *
 * <p>이름표 없는 보유는 VB로 센다(시스템 전체 관례 {@link StrategyBucket#orDefault}).
 * 증권사 잔고에만 있고 DB에 없는 보유는 어느 칸 것인지 알 수 없어 넣지 않는다.
 */
@Component
@Profile("paper")
public class SleeveEquityCalculator {

    static final String STALE = "잔고 정보가 낡음(조회 실패) — 보유 평가손익을 계산할 수 없음";
    static final String UNPRICED_SALE_TODAY =
            "오늘 판 거래의 매도 가격을 아직 모름(분봉은 장 마감 뒤 15:40에 쌓임) — 오늘은 판정 보류";

    /** 계산 결과 — 둘 중 하나만 있다: 칸 자산, 또는 보류 사유 */
    public record Result(SleeveEquity equity, String deferredReason) {
        static Result ok(SleeveEquity equity) {
            return new Result(equity, null);
        }

        static Result deferred(String reason) {
            return new Result(null, reason);
        }

        public boolean isDeferred() {
            return equity == null;
        }
    }

    private final BucketProperties properties;
    private final PositionRepository positionRepository;
    private final SleeveRealizedLedger ledger;
    private final Clock clock;

    public SleeveEquityCalculator(BucketProperties properties, PositionRepository positionRepository,
                                  SleeveRealizedLedger ledger, Clock clock) {
        this.properties = properties;
        this.positionRepository = positionRepository;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** @param account 지금의 잔고 스냅샷 (보유가 없으면 낡았거나 null이어도 된다) */
    public Result compute(StrategyBucket bucket, Account account) {
        SleeveRealized realized = ledger.realized(bucket);
        if (LocalDate.now(clock).equals(realized.latestUnmeasurableDate())) {
            return Result.deferred(UNPRICED_SALE_TODAY);
        }

        List<Position> holdings = holdingsOf(bucket);
        if (!holdings.isEmpty() && (account == null || !account.isFresh())) {
            return Result.deferred(STALE);
        }

        double unrealized = 0;
        for (Position pos : holdings) {
            Optional<String> problem = problemWith(pos, account);
            if (problem.isPresent()) return Result.deferred(problem.get());
            unrealized += pos.getQuantity() * (priceOf(pos, account) - pos.getAveragePrice());
        }

        SleeveEquity equity = new SleeveEquity(bucket, properties.allocationOf(bucket), realized, unrealized);
        if (!Double.isFinite(equity.equity())) {
            return Result.deferred("칸 자산 계산값이 숫자가 아님");
        }
        return Result.ok(equity);
    }

    private List<Position> holdingsOf(StrategyBucket bucket) {
        return positionRepository.findAll().stream()
                .filter(p -> p.getQuantity() > 0)
                .filter(p -> StrategyBucket.orDefault(p.getBucket()) == bucket)
                .toList();
    }

    /** DB 보유와 증권사 잔고가 같은 이야기를 하는지 — 아니면 그 사유 */
    private static Optional<String> problemWith(Position pos, Account account) {
        String code = pos.getStockCode();
        Optional<Account.PositionSnapshot> broker = snapshotOf(code, account);
        if (broker.isEmpty()) {
            return Optional.of(String.format("증권사 잔고에 없음: %s (DB %d주) — 체결 반영 대기", code, pos.getQuantity()));
        }
        if (broker.get().quantity() != pos.getQuantity()) {
            return Optional.of(String.format("수량 불일치: %s DB %d주 vs 증권사 %d주 — 체결 반영 대기",
                    code, pos.getQuantity(), broker.get().quantity()));
        }
        if (!(broker.get().currentPrice() > 0) || !Double.isFinite(broker.get().currentPrice())) {
            return Optional.of("현재가 없음: " + code);
        }
        if (!(pos.getAveragePrice() > 0)) {
            return Optional.of("평균단가 없음: " + code);
        }
        return Optional.empty();
    }

    private static double priceOf(Position pos, Account account) {
        return snapshotOf(pos.getStockCode(), account).orElseThrow().currentPrice();
    }

    private static Optional<Account.PositionSnapshot> snapshotOf(String code, Account account) {
        return account.getPositions().stream()
                .filter(s -> s.stockCode().equals(code))
                .findFirst();
    }
}
