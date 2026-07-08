package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 생성자에 List<RiskRule>을 받으면 스프링이 @Component로 등록된
 * 모든 RiskRule 구현체를 자동으로 모아서 주입해준다.
 * 룰을 추가/삭제할 때 이 클래스는 절대 건드릴 필요가 없다.
 */
@Component
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final List<RiskRule> rules;

    public RiskEngine(List<RiskRule> rules) {
        this.rules = rules;
        log.info("[RiskEngine] Loaded {} risk rules: {}", rules.size(),
                rules.stream().map(r -> r.getClass().getSimpleName()).collect(Collectors.joining(", ")));
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
