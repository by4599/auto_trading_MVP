package com.trading.risk;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모드 전환 이력을 <b>독립 트랜잭션</b>으로 한 줄 넣는다 (2026-09-22, 감사 24_audit M-1 대응).
 *
 * <p><b>왜 별도 빈인가 — 이게 이 클래스의 존재 이유다.</b>
 * 같은 클래스 안의 메서드를 부르면 스프링 프록시를 타지 않아 {@code @Transactional}이
 * 아예 걸리지 않는다. 그래서 기록기({@link ModeTransitionRecorder})와 <b>다른 빈</b>으로
 * 떼어놓아야 {@code REQUIRES_NEW}가 실제로 동작한다.
 *
 * <p><b>왜 {@code REQUIRES_NEW}인가.</b> {@code changeMode}는 <b>남의 트랜잭션 안에서</b>
 * 불릴 수 있다. 실제 경로:
 *
 * <pre>
 * StopLossArmer.onOrderFilled  @Transactional(REQUIRES_NEW)   ← 트랜잭션 열림
 *   → arm() → MarketDataService.getDailyCandles()             ← 그 안에서 KIS HTTP
 *   → KisApiClient.recordFailure()/recordSuccess()
 *   → TradingStatusManager.changeMode()  → 여기
 * </pre>
 *
 * 여기서 {@code REQUIRES_NEW} 없이 그냥 저장하면 INSERT가 <b>바깥 트랜잭션에 합류</b>하고,
 * 실패 시 그 트랜잭션이 rollback-only가 되어 <b>방금 장착한 손절선이 롤백된다</b>
 * (호출부는 "손절선 장착" 로그를 찍고도 실제로는 안 걸린 상태가 된다).
 * 손절선 누락은 2026-08-04에 이미 한 번 사고가 났던 자리다 — 새 실패 경로를 만들면 안 된다.
 * 같은 형태가 {@code ResearchController.addToWatchlist}(@Transactional + KIS 호출)에도 있다.
 *
 * <p>{@code REQUIRES_NEW}는 바깥 트랜잭션을 <b>잠시 멈추고</b> 새 트랜잭션으로 INSERT한다.
 * 그래서 여기서 나는 실패는 여기서 끝나고, 예외는 {@link ModeTransitionRecorder#record}의
 * try/catch로 올라간다 — 바깥은 오염되지 않는다.
 */
@Component
@Profile("!backtest")
public class ModeTransitionWriter {

    private final ModeTransitionRepository repository;

    public ModeTransitionWriter(ModeTransitionRepository repository) {
        this.repository = repository;
    }

    /** 경계는 이 INSERT 한 줄뿐이다 — 이보다 작아질 수 없다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveInNewTransaction(ModeTransition transition) {
        repository.save(transition);
    }
}
