package com.trading.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Supabase(PostgREST)로 운영 스냅샷을 upsert하는 구현체.
 *
 * 같은 id 행을 계속 덮어쓴다(on_conflict=id) — 히스토리가 아니라 "지금 상태" 한 줄이다.
 * paper 프로파일 전용: 백테스트에 환경변수가 남아 있어도 빈 자체가 만들어지지 않는다
 * (텔레그램이 OS 환경변수로 백테스트에 샜던 사고의 구조적 차단과 같은 방식).
 *
 * 전송 실패는 로그만 남기고 삼킨다 — 미러가 매매를 멈추게 해선 안 된다.
 */
@Component
@Profile("paper")
public class SupabaseMirrorClient implements MirrorPublisher {

    private static final Logger log = LoggerFactory.getLogger(SupabaseMirrorClient.class);

    private final SupabaseProperties props;
    private final RestClient restClient;

    public SupabaseMirrorClient(SupabaseProperties props) {
        this.props = props;
        // 네트워크 장애 시 빠른 실패 — 미러 실패는 무시되므로 무한 대기보다 조기 포기가 안전하다
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Override
    public boolean publish(MirrorSnapshot snapshot) {
        if (!props.isConfigured()) {
            log.debug("Supabase 미설정 — 미러 전송 스킵");
            return false;
        }
        try {
            restClient.post()
                    .uri(props.baseUrl() + "/rest/v1/" + props.getTable() + "?on_conflict=id")
                    .header("apikey", props.getKey())
                    .header("Authorization", "Bearer " + props.getKey())
                    .header("Prefer", "resolution=merge-duplicates,return=minimal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(snapshot.toRow()))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("[미러] Supabase 전송 완료 — 보유 {}종목", snapshot.holdings().size());
            return true;
        } catch (Exception e) {
            log.warn("[미러] Supabase 전송 실패 — 매매에는 영향 없음: {}", e.getMessage());
            return false;
        }
    }
}
