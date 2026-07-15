package com.trading;

import com.trading.position.Position;
import com.trading.position.PositionRepository;
import com.trading.risk.TradingMode;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 데드맨 스위치 (OPERATIONS §2) — 앱 생존을 외부 서비스에 알리는 하트비트.
 * 실제 네트워크 대신 MockRestServiceServer로 RestClient 호출을 검증한다.
 */
@DisplayName("DeadmanHeartbeat — 데드맨 스위치 ping")
class DeadmanHeartbeatTest {

    private HeartbeatProperties props;
    private TradingStatusManager statusManager;
    private PositionRepository positionRepository;
    private MockRestServiceServer mockServer;
    private DeadmanHeartbeat sut;

    @BeforeEach
    void setUp() {
        props = new HeartbeatProperties();
        statusManager = new TradingStatusManager();
        positionRepository = mock(PositionRepository.class);
        when(positionRepository.findAll()).thenReturn(List.of());

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        sut = new DeadmanHeartbeat(props, statusManager, positionRepository, builder.build());
    }

    @Test
    @DisplayName("URL 미설정 → ping 전송 안 함")
    void skips_when_url_not_configured() {
        sut.ping();

        mockServer.verify(); // 어떤 요청도 기대하지 않았으므로 요청이 없어야 통과
    }

    @Test
    @DisplayName("URL 설정됨 → POST로 ping 전송")
    void sends_ping_when_url_configured() {
        props.setUrl("https://hc-ping.com/test-uuid");
        mockServer.expect(requestTo("https://hc-ping.com/test-uuid"))
                .andExpect(method(POST))
                .andRespond(withSuccess());

        sut.ping();

        mockServer.verify();
    }

    @Test
    @DisplayName("payload에 현재 모드와 보유종목 수(수량>0만 집계)가 포함된다")
    void payload_reflects_mode_and_held_position_count() {
        statusManager.changeMode(TradingMode.SAFE_MODE);
        Position held = Position.empty("005930");
        held.applyBuy(5, 70_000);
        Position emptied = Position.empty("000660"); // quantity 0 — 집계 제외돼야 함
        when(positionRepository.findAll()).thenReturn(List.of(held, emptied));

        String payload = sut.heartbeatPayload();

        assertThat(payload).contains("SAFE_MODE").contains("positions=1");
    }

    @Test
    @DisplayName("전송 실패(5xx)해도 예외가 밖으로 새지 않는다")
    void ping_failure_does_not_propagate() {
        props.setUrl("https://hc-ping.com/test-uuid");
        mockServer.expect(requestTo("https://hc-ping.com/test-uuid"))
                .andExpect(method(POST))
                .andRespond(withServerError());

        sut.ping(); // 예외가 밖으로 새면 테스트 실패

        mockServer.verify();
    }
}
