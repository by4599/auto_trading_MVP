package com.trading.signal;

public class Signal {

    public enum Type { BUY, SELL }

    private final Type type;
    private final String stockCode;
    private final String strategyName;

    private Signal(Type type, String stockCode, String strategyName) {
        this.type         = type;
        this.stockCode    = stockCode;
        this.strategyName = strategyName;
    }

    public static Signal buy(String stockCode, String strategyName) {
        return new Signal(Type.BUY, stockCode, strategyName);
    }

    public static Signal sell(String stockCode, String strategyName) {
        return new Signal(Type.SELL, stockCode, strategyName);
    }

    public boolean isBuy()  { return type == Type.BUY; }
    public boolean isSell() { return type == Type.SELL; }

    public String getStockCode()    { return stockCode; }
    public String getStrategyName() { return strategyName; }
    public Type   getType()         { return type; }

    @Override
    public String toString() {
        return "Signal{type=" + type + ", stock=" + stockCode + ", strategy=" + strategyName + "}";
    }
}
