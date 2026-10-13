package com.trading.mirror;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Supabase 전송 구현체 — 실패해도 예외를 던지지 않는다는 계약을 검증한다.
 */
@DisplayName("SupabaseMirrorClient — 전송 실패를 삼킨다")
class SupabaseMirrorClientTest {

    private static MirrorSnapshot sampleSnapshot() {
        return new MirrorSnapshot("paper", "2026-08-20T10:00:00+09:00", "RUNNING", true,
                50_000_000L, 1.53, 0, 3, 20_000L, 9_000L, List.of());
    }

    @Test
    @DisplayName("url·key 미설정이면 전송을 시도하지 않고 false를 반환한다")
    void skipsWhenNotConfigured() {
        SupabaseMirrorClient client = new SupabaseMirrorClient(new SupabaseProperties());

        assertThat(client.publish(sampleSnapshot())).isFalse();
    }

    @Test
    @DisplayName("전송이 실패해도 예외 대신 false를 돌려준다")
    void returnsFalseOnFailure() {
        SupabaseProperties props = new SupabaseProperties();
        props.setUrl("http://127.0.0.1:1");   // 연결 거부 — 즉시 실패
        props.setKey("secret-key-for-test");
        SupabaseMirrorClient client = new SupabaseMirrorClient(props);

        assertThatCode(() -> assertThat(client.publish(sampleSnapshot())).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("기준 URL의 뒤 슬래시는 제거한다 — 경로가 //rest/v1로 겹치지 않게")
    void normalizesTrailingSlash() {
        SupabaseProperties props = new SupabaseProperties();
        props.setUrl("https://example.supabase.co/");

        assertThat(props.baseUrl()).isEqualTo("https://example.supabase.co");
    }
}
