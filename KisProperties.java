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
     * 앱 시작 시점에 필수 값이 없으면 즉시 실패시킨다.
     * 환경 변수 누락과 실전/모의 URL 혼동을 첫 번째 API 호출 전에 발견한다.
     */
    @PostConstruct
    void validate() {
        Assert.hasText(baseUrl,   "KIS base-url이 설정되지 않았습니다 (application-paper.yml: kis.base-url)");
        Assert.hasText(appkey,    "KIS_APPKEY 환경 변수가 설정되지 않았습니다");
        Assert.hasText(secretkey, "KIS_SECRETKEY 환경 변수가 설정되지 않았습니다");
        Assert.hasText(accountNo, "KIS_ACCOUNT_NO 환경 변수가 설정되지 않았습니다 (예: 50000000-01)");

        // paper 프로필에서 실전 URL을 잘못 설정하면 실계좌로 주문이 나가는 사고가 발생한다.
        // 모의투자 URL(openapivts.koreainvestment.com)이 아니면 시작을 거부한다.
        Assert.isTrue(
            baseUrl.contains("openapivts"),
            "paper 프로필에서 실전 URL이 감지되었습니다: " + baseUrl
            + " → 모의투자 URL(openapivts.koreainvestment.com:29443)인지 확인하세요"
        );
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
