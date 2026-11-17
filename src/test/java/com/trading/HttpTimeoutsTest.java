package com.trading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 연결은 받고 아무 대답도 안 하는 서버 — 시간 제한이 없으면 HTTP 호출이 끝나지 않는다.
 * 뉴스·공시 수집이 이렇게 매달리면 I/O 스레드가 묶여 하트비트·미러가 밀린다(42_audit M-1).
 *
 * <p>호출은 데몬 스레드에서 하고 3초만 기다린다 — 제한이 빠지면 시험이 멈추지 않고 "실패"로 끝나야 하기 때문이다
 * (재시도 연결이 대기열에 걸려 시험 실행 전체가 멈춘 적이 있다).
 */
@DisplayName("HttpTimeouts — 대답 없는 서버 앞에서 무한정 기다리지 않는다")
class HttpTimeoutsTest {

    @Test
    @Timeout(10)
    @DisplayName("읽기 시간 제한(300ms)으로 곧바로 실패한다 — 제한이 없으면 3초 안에 끝나지 않아 실패한다")
    void read_timeout_cuts_a_silent_server() throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor(daemon("http-timeout-caller"));
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            startSilentAcceptor(server);
            RestClient client = RestClient.builder().requestFactory(HttpTimeouts.requestFactory(500, 300)).build();
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/";

            Future<String> call = caller.submit(() -> client.get().uri(url).retrieve().body(String.class));

            assertThatThrownBy(() -> call.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResourceAccessException.class);
        } finally {
            caller.shutdownNow();
        }
    }

    /** 연결을 받기만 하고 아무것도 보내지 않는다 — 클라이언트의 재시도 연결까지 받아 둔다 */
    private static void startSilentAcceptor(ServerSocket server) {
        Thread acceptor = new Thread(() -> {
            List<Socket> held = new ArrayList<>();
            try {
                while (!server.isClosed()) held.add(server.accept());
            } catch (Exception e) {
                // 서버 소켓이 닫히면 여기로 온다 — 끝
            } finally {
                held.forEach(HttpTimeoutsTest::closeQuietly);
            }
        }, "silent-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
            // 정리 중 예외는 무시
        }
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
