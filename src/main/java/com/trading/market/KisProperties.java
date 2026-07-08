package com.trading.market;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * application-paper.yml의 kis.* 값을 바인딩한다.
 * 실전투자로 전환할 때는 application-real.yml을 추가하고 base-url만 바꾸면 된다.
 */
@ConfigurationProperties(prefix = "kis")
public class KisProperties {

    private String baseUrl;
    private String appkey;
    private String secretkey;
    private String accountNo;

    /**
     * 자격증명이 미설정된 상태면 설정 UI(localhost:8080) 안내만 하고 기동을 허용한다.
     * 자격증명이 설정된 상태에서는 실전 URL 혼용만 차단한다.
     */
    @PostConstruct
    void validate() {
        if (!isConfigured()) {
            System.out.println("[KisProperties] KIS 자격증명 미설정 — http://localhost:8080 에서 입력 후 재시작하세요");
            return;
        }
        Assert.isTrue(
            baseUrl.contains("openapivts"),
            "paper 프로필에서 실전 URL이 감지되었습니다: " + baseUrl
            + " → 모의투자 URL(openapivts.koreainvestment.com:29443)인지 확인하세요"
        );
    }

    public boolean isConfigured() {
        return baseUrl    != null && !baseUrl.isBlank()
            && appkey     != null && !appkey.isBlank()
            && secretkey  != null && !secretkey.isBlank()
            && accountNo  != null && !accountNo.isBlank();
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getAppkey() { return appkey; }
    public void setAppkey(String appkey) { this.appkey = appkey; }

    public String getSecretkey() { return secretkey; }
    public void setSecretkey(String secretkey) { this.secretkey = secretkey; }

    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
}
