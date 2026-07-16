package com.trading.market;

import com.trading.NotificationService;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * KIS API 장애 대응 (OPERATIONS §4) — 토큰 발급 재시도, 연속 호출 실패 시 SAFE_MODE 전환.
 * 실제 네트워크 대신 MockRestServiceServer로 tokenClient/apiClient 각각의
 * RestClient 호출을 검증한다.
 */
@DisplayName("KisApiClient — 토큰 재시도 · 연속 실패 SAFE_MODE 전환")
class KisApiClientTest {

    private static final String TOKEN_JSON = "{\"access_token\":\"test-token\",\"expires_in\":86400}";

    private KisProperties props;
    private TradingStatusManager statusManager;
    private NotificationService notifier;
    private RestClient.Builder tokenBuilder;
    private RestClient.Builder apiBuilder;
    private MockRestServiceServer tokenMock;
    private MockRestServiceServer apiMock;

    @BeforeEach
    void setUp() {
        props = configuredProps();
        statusManager = new TradingStatusManager();
        notifier = mock(NotificationService.class);

        tokenBuilder = RestClient.builder();
        tokenMock = MockRestServiceServer.bindTo(tokenBuilder).build();

        apiBuilder = RestClient.builder();
        apiMock = MockRestServiceServer.bindTo(apiBuilder).build();
    }

    private static KisProperties configuredProps() {
        KisProperties p = new KisProperties();
        p.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        p.setAppkey("test-appkey");
        p.setSecretkey("test-secretkey");
        p.setAccountNo("50000000-01");
        return p;
    }

    private KisApiClient sut() {
        return new KisApiClient(props, statusManager, notifier, tokenBuilder, apiBuilder);
    }

    // ── 토큰 발급 재시도 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰 발급 3회 연속 실패 → SAFE_MODE 전환 + 알림, 예외 전파")
    void token_refresh_fails_three_times_triggers_safe_mode() {
        tokenMock.expect(ExpectedCount.times(3), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        KisApiClient sut = sut();

        assertThatThrownBy(sut::getBearerToken).isInstanceOf(IllegalStateException.class);
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.SAFE_MODE);
        verify(notifier, atLeastOnce()).sendCritical(anyString());
        tokenMock.verify();
    }

    @Test
    @DisplayName("토큰 발급 1회차 실패 후 2회차 성공 → SAFE_MODE 전환 없이 토큰 반환")
    void token_refresh_succeeds_on_retry() {
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));

        String token = sut().getBearerToken();

        assertThat(token).isEqualTo("test-token");
        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
        verify(notifier, never()).sendCritical(anyString());
        tokenMock.verify();
    }

    // ── 연속 API 호출 실패 ────────────────────────────────────────────────────

    @Test
    @DisplayName("연속 3회 API 호출 실패(4xx) → SAFE_MODE 전환 + 알림")
    void three_consecutive_api_failures_trigger_safe_mode() {
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        apiMock.expect(ExpectedCount.times(3), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        KisApiClient sut = sut();
        for (int i = 0; i < 3; i++) {
            try {
                sut.getClient().get().uri("/test").retrieve().toBodilessEntity();
            } catch (Exception ignored) {
                // RestClient.retrieve()는 4xx에서 예외를 던진다 — intercept()는 이미 실행된 뒤라 무관
            }
        }

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.SAFE_MODE);
        verify(notifier, atLeastOnce()).sendCritical(anyString());
        apiMock.verify();
    }

    @Test
    @DisplayName("성공 응답이 섞이면 연속 실패 카운터가 리셋된다 — 3회 미만이면 SAFE_MODE 전환 없음")
    void success_resets_consecutive_failure_counter() {
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
        apiMock.expect(ExpectedCount.once(), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        apiMock.expect(ExpectedCount.once(), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess());
        apiMock.expect(ExpectedCount.once(), requestTo("/test"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        KisApiClient sut = sut();
        for (int i = 0; i < 3; i++) {
            try {
                sut.getClient().get().uri("/test").retrieve().toBodilessEntity();
            } catch (Exception ignored) {
            }
        }

        assertThat(statusManager.getCurrentMode()).isEqualTo(TradingMode.RUNNING);
        verify(notifier, never()).sendCritical(anyString());
        apiMock.verify();
    }
}
