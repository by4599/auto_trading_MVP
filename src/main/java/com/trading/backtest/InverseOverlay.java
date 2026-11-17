package com.trading.backtest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 하락장 인버스 ETF 덧붙이기 (63_quant §0.3) — 검증 창 하나의 A동 하루 마감 기록 위에 인버스 보유를 얹어 합성 자산을 계산한다.
 * 순수 계산이다(엔진·DB·스프링 무관). 하락장 판정은 호출자가 넘긴다(전일 종가 기준 — 당일 봉 미사용).
 *
 * <p>규칙: 하락장 날 시가 매수(목표 w × 전일 합성 자산, 전일 A동 현금 + 확정 손익 한도) → 하락장이 아닌 첫날 시가 매도.
 * 변형 E1(고점 대비 트레일) · E2(진입가 고정 손절) · E3(최대 보유일 종가)로 나가면 하락장 아닌 날이 올 때까지 재진입 금지.
 * 창 마지막 날 보유분은 그날 종가에 판다. 비용은 주식과 같다(매도 제세 포함 — 보수적).
 */
final class InverseOverlay {

    static final String CODE = "114800";
    static final double TRAIL_PCT = 0.08;
    static final double STOP_PCT = 0.07;
    static final int MAX_HOLD_DAYS = 40;

    enum Exit { E0, E1, E2, E3 }

    record Bar(LocalDate date, double open, double high, double low, double close) {}

    record Config(String code, double weight, Exit exit) {
        static Config of(double weight, Exit exit) {
            return new Config(String.format("W%.0f-%s", weight * 100, exit), weight, exit);
        }
    }

    /** 창 하나의 결과 — 합성 하루 마감 자산(A동 기록과 같은 길이)과 인버스 확정 거래 */
    record WindowOutcome(List<Double> composite, List<TradeRecorder.ClosedTrade> trades) {}

    private InverseOverlay() {
    }

    static WindowOutcome simulate(List<ScaleInRunStats.DayEnd> tape, Map<LocalDate, Bar> bars,
                                  Predicate<LocalDate> bearDay, Config cfg, double slippage, double initialCash) {
        Book book = new Book(cfg, slippage);
        List<Double> composite = new ArrayList<>(tape.size());
        for (int i = 0; i < tape.size(); i++) {
            ScaleInRunStats.DayEnd day = tape.get(i);
            double prevEquity = i == 0 ? initialCash : composite.get(i - 1);
            double prevCash = i == 0 ? initialCash : tape.get(i - 1).cash();
            Bar bar = bars.get(day.date());
            boolean lastDay = i == tape.size() - 1;
            if (bar != null) {
                book.step(i, bar, bearDay.test(day.date()), prevEquity, prevCash, lastDay);
            } else if (lastDay) {
                book.closeAtLastClose(day.date());   // 마지막 날 인버스 봉이 없으면 직전 종가로 정리
            }
            composite.add(day.equity() + book.pnlAt(bar));
        }
        return new WindowOutcome(List.copyOf(composite), List.copyOf(book.trades));
    }

    /** 인버스 장부 — 창 하나 동안만 산다 */
    private static final class Book {
        private final Config cfg;
        private final double slippage;
        private final List<TradeRecorder.ClosedTrade> trades = new ArrayList<>();
        private double realized;
        private int qty;
        private double cashOut;
        private double entryRaw;
        private double peak;
        private int entryIndex;
        private LocalDate entryDate;
        private boolean blocked;
        private double lastClose = Double.NaN;

        Book(Config cfg, double slippage) {
            this.cfg = cfg;
            this.slippage = slippage;
        }

        void step(int i, Bar bar, boolean bear, double prevEquity, double prevCash, boolean lastDay) {
            boolean exitedToday = false;
            if (qty > 0) exitedToday = manageHolding(i, bar, bear);
            if (!bear) blocked = false;
            if (qty == 0 && bear && !blocked && !exitedToday) enter(i, bar, prevEquity, prevCash);
            if (qty > 0) peak = Math.max(peak, bar.close());
            if (qty > 0 && lastDay) close(bar.date(), bar.close(), "WindowEnd");
            lastClose = bar.close();
        }

        /** @return 오늘 팔았으면 true */
        private boolean manageHolding(int i, Bar bar, boolean bear) {
            if (!bear) {
                close(bar.date(), bar.open(), "RegimeUp");
                return true;
            }
            if (stopHit(bar)) {
                blocked = true;
                return true;
            }
            if (cfg.exit() == Exit.E3 && i - entryIndex >= MAX_HOLD_DAYS) {
                close(bar.date(), bar.close(), "MaxHold");
                blocked = true;
                return true;
            }
            return false;
        }

        private void enter(int i, Bar bar, double prevEquity, double prevCash) {
            double fill = BacktestCosts.buyFillPrice(bar.open(), slippage);
            double budget = Math.min(cfg.weight() * prevEquity, prevCash + realized);
            int q = (int) Math.floor(budget / (fill * (1 + BacktestCosts.COMMISSION_RATE)));
            if (q <= 0) return;
            qty = q;
            cashOut = BacktestCosts.buyCashOut(fill, q);
            entryRaw = bar.open();
            peak = bar.open();
            entryIndex = i;
            entryDate = bar.date();
            if (stopHit(bar)) blocked = true;   // 진입일에도 손절 판정(불리하게)
        }

        /** E1·E2 손절 — 시가가 이미 아래면 시가, 아니면 저가가 닿으면 손절선 가격 */
        private boolean stopHit(Bar bar) {
            double stop = stopLevel();
            if (Double.isNaN(stop)) return false;
            if (bar.open() <= stop) {
                close(bar.date(), bar.open(), cfg.exit() == Exit.E1 ? "Trail" : "Stop");
                return true;
            }
            if (bar.low() <= stop) {
                close(bar.date(), stop, cfg.exit() == Exit.E1 ? "Trail" : "Stop");
                return true;
            }
            return false;
        }

        private double stopLevel() {
            return switch (cfg.exit()) {
                case E1 -> peak * (1 - TRAIL_PCT);
                case E2 -> entryRaw * (1 - STOP_PCT);
                default -> Double.NaN;
            };
        }

        private void close(LocalDate date, double rawPrice, String reason) {
            double fill = BacktestCosts.sellFillPrice(rawPrice, slippage);
            double cashIn = BacktestCosts.sellCashIn(fill, qty);
            trades.add(new TradeRecorder.ClosedTrade(CODE, entryDate, date, qty, cashOut, cashIn, reason));
            realized += cashIn - cashOut;
            qty = 0;
            cashOut = 0;
        }

        void closeAtLastClose(LocalDate date) {
            if (qty > 0 && !Double.isNaN(lastClose)) close(date, lastClose, "WindowEnd");
        }

        /** 오늘 마감 기준 인버스 손익 = 확정 손익 + 보유 평가(종가 × 수량 − 매수 현금 유출) */
        double pnlAt(Bar bar) {
            if (qty == 0) return realized;
            double close = bar != null ? bar.close() : lastClose;
            return realized + qty * close - cashOut;
        }
    }
}
