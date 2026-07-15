package com.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 데드맨 스위치(OPERATIONS §2) 하트비트 대상 URL — healthchecks.io 등
 * 외부 감시 서비스의 ping 엔드포인트. 비어 있으면 DeadmanHeartbeat가 전송을 스킵한다.
 */
@ConfigurationProperties(prefix = "heartbeat")
public class HeartbeatProperties {

    private String url = "";

    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
