package com.trading.risk;

import com.trading.position.Account;
import com.trading.signal.Signal;

public interface RiskRule {
    RiskResult validate(Signal signal, Account account);
}
