package com.trading.backtest;

import com.trading.position.Position;
import com.trading.position.PositionRepository;

/**
 * "보유 중"의 단일 정의 — 수량이 1주 이상인 포지션만 보유로 본다.
 *
 * <p>진입부와 청산부가 같은 판정을 쓰도록 한곳에 둔다(수량 0 잔재를 보유로 세면
 * 중복 진입·헛매도가 난다).
 */
final class HeldPositions {

    private HeldPositions() {
    }

    /** 보유분이 없으면 null */
    static Position of(PositionRepository positionRepository, String stockCode) {
        return positionRepository.findByStockCode(stockCode)
                .filter(p -> p.getQuantity() > 0)
                .orElse(null);
    }
}
