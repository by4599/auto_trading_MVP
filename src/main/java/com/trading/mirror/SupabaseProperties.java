package com.trading.mirror;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application-paper.yml의 supabase.* 값을 바인딩한다.
 *
 * url 또는 key가 비어 있으면 미러 전송을 통째로 스킵한다 (미설정 환경 graceful degradation).
 * key는 서버 전용 secret key다 — yml·코드에 값을 적지 않고 환경변수로만 주입한다
 * (공개용 publishable key로는 RLS가 켜진 테이블에 쓸 수 없다).
 */
@ConfigurationProperties(prefix = "supabase")
public class SupabaseProperties {

    private String url        = "";
    private String key        = "";
    private String table      = "trading_mirror";
    private String snapshotId = "paper";
    private long   intervalMs = 300_000L;

    /** url·key가 둘 다 있어야 전송한다 */
    public boolean isConfigured() {
        return !url.isBlank() && !key.isBlank();
    }

    /** 뒤 슬래시를 제거한 기준 URL — PostgREST 경로와 이어붙이기 위함 */
    public String baseUrl() {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
}
