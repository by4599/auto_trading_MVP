package com.trading.risk;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매수 차단 이력을 <b>독립 트랜잭션</b>으로 한 줄 넣는다 (2026-09-22, 감사 24_audit M-1 대응).
 *
 * <p>{@link ModeTransitionWriter}와 같은 이유로 <b>별도 빈</b>이다 — 같은 클래스 안에서
 * 부르면 스프링 프록시를 안 타서 {@code @Transactional}이 걸리지 않는다.
 *
 * <p>지금 호출부({@code TradingScheduler.run})는 트랜잭션이 없지만, 이력 기록이
 * <b>남의 트랜잭션에 합류해 그걸 롤백시킬 수 있는 구조</b>를 남겨둘 이유가 없다.
 * 모드 전환 쪽에서 실제로 그 일이 일어날 수 있음이 확인됐다(위 클래스 주석).
 */
@Component
@Profile("!backtest")
public class RiskBlockWriter {

    private final RiskBlockRecordRepository repository;

    public RiskBlockWriter(RiskBlockRecordRepository repository) {
        this.repository = repository;
    }

    /** 경계는 이 INSERT 한 줄뿐이다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveInNewTransaction(RiskBlockRecord record) {
        repository.save(record);
    }
}
