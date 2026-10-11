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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        // 바로 실행하는 실행기 — 기존 검증(요청·실패 삼킴)은 넘겨받은 작업이 곧바로 돈다고 보고 쓴다
        sut = new DeadmanHeartbeat(props, statusManager, positionRepository, builder.build(), Runnable::run);
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

    // ── 박동 판단은 기본 스케줄러 스레드, 느린 HTTP만 I/O 스레드 (BACKLOG [2026-09-21]) ──

    @Test
    @DisplayName("박동 내용은 호출(기본 스케줄러) 스레드에서 만들고, HTTP는 전송 실행기로 넘긴다 — 감시 루프를 붙잡지 않는다")
    void ping_hands_http_to_the_send_executor() {
        props.setUrl("https://hc-ping.com/test-uuid");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://hc-ping.com/test-uuid"))
                .andExpect(method(POST))
                .andRespond(withSuccess());
        List<Runnable> handedOff = new ArrayList<>();
        DeadmanHeartbeat deferred =
                new DeadmanHeartbeat(props, statusManager, positionRepository, builder.build(), handedOff::add);

        deferred.ping();

        verify(positionRepository).findAll();                                    // 박동 내용은 이미 만들어졌다
        assertThat(handedOff).hasSize(1);                                         // HTTP는 넘겨졌을 뿐
        assertThatThrownBy(server::verify).isInstanceOf(AssertionError.class);   // 아직 안 나갔다
        handedOff.get(0).run();
        server.verify();
    }

    @Test
    @DisplayName("I/O 실행기가 거부해도(앱 종료 중) 예외가 밖으로 새지 않는다")
    void rejected_hand_off_does_not_propagate() {
        props.setUrl("https://hc-ping.com/test-uuid");
        DeadmanHeartbeat rejecting = new DeadmanHeartbeat(props, statusManager, positionRepository,
                RestClient.create(), task -> { throw new RejectedExecutionException("종료 중"); });

        assertThatCode(rejecting::ping).doesNotThrowAnyException();
    }
}
