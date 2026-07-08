package com.trading.risk;

public class RiskResult {

    private final boolean pass;
    private final String  reason;

    private RiskResult(boolean pass, String reason) {
        this.pass   = pass;
        this.reason = reason;
    }

    public static RiskResult pass()              { return new RiskResult(true,  null); }
    public static RiskResult reject(String why)  { return new RiskResult(false, why); }

    public boolean isPass()   { return pass; }
    public String  getReason(){ return reason; }
}
