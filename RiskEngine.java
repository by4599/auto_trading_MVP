package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 생성자에 List<RiskRule>을 받으면 스프링이 @Component로 등록된
 * 모든 RiskRule 구현체를 자동으로 모아서 주입해준다.
 * 룰을 추가/삭제할 때 이 클래스는 절대 건드릴 필요가 없다.
 */
@Component
public class RiskEngine {

    private final List<RiskRule> rules;

    public RiskEngine(List<RiskRule> rules) {
        this.rules = rules;
    }

    public RiskResult check(Signal signal, Account account) {
        for (RiskRule rule : rules) {
            RiskResult result = rule.validate(signal, account);
            if (!result.isPass()) {
                return result;
            }
        }
        return RiskResult.pass();
    }
}
