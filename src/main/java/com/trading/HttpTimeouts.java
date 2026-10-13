package com.trading;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * 연결·읽기 시간 제한을 건 요청 팩토리.
 *
 * <p>{@code RestClient.builder()}를 팩토리 없이 쓰면 JDK 기본 클라이언트가 붙는데, 연결·읽기 제한이 없다 —
 * 대답 없는 서버 하나가 호출 스레드를 무기한 묶는다(42_audit M-1). 읽기 제한은 "바이트 사이 침묵" 기준이라
 * 흐르고 있는 큰 다운로드는 끊지 않는다.
 */
public final class HttpTimeouts {

    private HttpTimeouts() {}

    public static SimpleClientHttpRequestFactory requestFactory(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return factory;
    }
}
