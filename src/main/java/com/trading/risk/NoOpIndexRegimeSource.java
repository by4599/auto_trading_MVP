package com.trading.risk;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 라이브(paper) 지수 레짐 데이터원 — 아직 실시간 지수 조회 미구현이라 항상 판정 불가.
 * 필터가 ON이어도 데이터가 없으면 통과시킨다 (IndexRegimeSource 계약).
 * 실구현은 필터가 A/B를 통과해 실제로 켤 때 추가한다 (YAGNI).
 */
@Component
@Profile("paper")
public class NoOpIndexRegimeSource implements IndexRegimeSource {

    @Override
    public Optional<Boolean> isBearishRegime() {
        return Optional.empty();
    }
}
