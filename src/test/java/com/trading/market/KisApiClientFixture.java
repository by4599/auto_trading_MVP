package com.trading.market;

import com.trading.NotificationService;
import com.trading.risk.TradingStatusManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 테스트 전용 — 다른 패키지의 KIS 래퍼 시험이 가짜 서버(MockRestServiceServer)에 묶인 {@link KisApiClient}를 얻는 통로.
 *
 * <p>{@link KisApiClient}의 시험용 생성자는 이 패키지 안에서만 보인다. 그 생성자를 거쳐야 운영과 같은 인터셉터(인증 헤더·
 * 재시도)가 붙은 채로 검증된다({@code KisMarketDataServiceTest}와 같은 조립). 토큰은 1회 발급으로 고정한다.
 */
public final class KisApiClientFixture {

    public static final String ACCOUNT_NO = "50000000-01";
    public static final String APPKEY = "test-appkey";
    public static final String SECRETKEY = "test-secretkey";
    public static final String TOKEN = "test-token";

    private KisApiClientFixture() {
    }

    /** 가짜 서버에 묶인 클라이언트와, 응답을 심을 가짜 서버(API 쪽) */
    public record Bound(KisApiClient client, MockRestServiceServer apiServer) {}

    public static Bound bind() {
        KisProperties props = new KisProperties();
        props.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        props.setAppkey(APPKEY);
        props.setSecretkey(SECRETKEY);
        props.setAccountNo(ACCOUNT_NO);

        RestClient.Builder tokenBuilder = RestClient.builder();
        MockRestServiceServer tokenServer = MockRestServiceServer.bindTo(tokenBuilder).build();
        tokenServer.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andRespond(withSuccess("{\"access_token\":\"" + TOKEN + "\",\"expires_in\":86400}",
                        MediaType.APPLICATION_JSON));

        RestClient.Builder apiBuilder = RestClient.builder();
        MockRestServiceServer apiServer = MockRestServiceServer.bindTo(apiBuilder).build();

        KisApiClient client = new KisApiClient(props, new TradingStatusManager(), mock(NotificationService.class),
                new KisRateLimiter(10_000), Clock.systemDefaultZone(), tokenBuilder, apiBuilder);
        return new Bound(client, apiServer);
    }
}
