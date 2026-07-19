package com.trading.signal;

import com.trading.bucket.StrategyBucket;

public class Signal {

    public enum Type { BUY, SELL }

    private final Type type;
    private final String stockCode;
    private final String strategyName;
    private final StrategyBucket bucket;

    private Signal(Type type, String stockCode, String strategyName, StrategyBucket bucket) {
        this.type         = type;
        this.stockCode    = stockCode;
        this.strategyName = strategyName;
        this.bucket       = bucket;
    }

    /** 기존 호출부 호환 — 칸 미지정 신호는 방식1(VB) 소속 */
    public static Signal buy(String stockCode, String strategyName) {
        return buy(stockCode, strategyName, StrategyBucket.VB);
    }

    public static Signal buy(String stockCode, String strategyName, StrategyBucket bucket) {
        return new Signal(Type.BUY, stockCode, strategyName, bucket);
    }

    /** 매도의 실적 귀속은 Position의 bucket이 원천 — 신호의 칸은 참고값이다 */
    public static Signal sell(String stockCode, String strategyName) {
        return new Signal(Type.SELL, stockCode, strategyName, StrategyBucket.VB);
    }

    public boolean isBuy()  { return type == Type.BUY; }
    public boolean isSell() { return type == Type.SELL; }

    public String getStockCode()    { return stockCode; }
    public String getStrategyName() { return strategyName; }
    public Type   getType()         { return type; }
    public StrategyBucket getBucket() { return bucket; }

    @Override
    public String toString() {
        return "Signal{type=" + type + ", stock=" + stockCode
                + ", strategy=" + strategyName + ", bucket=" + bucket + "}";
    }
}
