package com.trading.research;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DART Open API 설정 (https://opendart.fss.or.kr — 무료, 인증키 발급 필요).
 * 키가 비어 있으면 공시 수집은 조용히 비활성화된다 (뉴스 수집은 영향 없음).
 */
@ConfigurationProperties(prefix = "dart")
public class DartProperties {

    private String apiKey;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
