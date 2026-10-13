package com.trading.mirror;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 외부(Supabase)로 복사할 운영 스냅샷 — 읽기 전용 거울이다.
 *
 * 이 값은 매매 판단으로 되돌아오지 않는다. 밖에서 상태를 눈으로 확인하기 위한 사본이며,
 * 전송이 실패해도 매매는 그대로 돈다.
 *
 * dailyPnlPercent는 사람이 읽는 퍼센트 단위다 (1.5 = +1.5%). 앱 내부(Account)의
 * 같은 이름 값은 비율(0.015)이므로 이 경계에서 한 번 환산한다.
 */
public record MirrorSnapshot(
        String  id,
        String  updatedAt,
        String  tradingMode,
        boolean accountFresh,
        long    totalAssetValue,
        double  dailyPnlPercent,
        int     consecutiveLossCount,
        int     runStreakDays,
        long    unrealizedPnl,
        long    realizedPnlToday,
        List<Holding> holdings
) {
    /** 보유 종목 1건 — stopPrice는 손절선 미장착 시 null */
    public record Holding(
            String stockCode,
            int    quantity,
            long   averagePrice,
            long   currentPrice,
            Long   stopPrice,
            long   unrealizedPnl,
            String bucket
    ) {
        Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stock_code",     stockCode);
            row.put("quantity",       quantity);
            row.put("average_price",  averagePrice);
            row.put("current_price",  currentPrice);
            row.put("stop_price",     stopPrice);
            row.put("unrealized_pnl", unrealizedPnl);
            row.put("bucket",         bucket);
            return row;
        }
    }

    /** PostgREST가 받을 한 행. null 값을 담아야 하므로 Map.of가 아닌 LinkedHashMap을 쓴다. */
    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id",                     id);
        row.put("updated_at",             updatedAt);
        row.put("trading_mode",           tradingMode);
        row.put("account_fresh",          accountFresh);
        row.put("total_asset_value",      totalAssetValue);
        row.put("daily_pnl_percent",      dailyPnlPercent);
        row.put("consecutive_loss_count", consecutiveLossCount);
        row.put("run_streak_days",        runStreakDays);
        row.put("unrealized_pnl",         unrealizedPnl);
        row.put("realized_pnl_today",     realizedPnlToday);
        row.put("holdings",               holdings.stream().map(Holding::toRow).toList());
        return row;
    }
}
