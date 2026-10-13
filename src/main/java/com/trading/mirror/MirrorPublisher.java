package com.trading.mirror;

/**
 * 운영 스냅샷을 외부 저장소로 내보내는 창구.
 *
 * 외부 연동은 인터페이스 뒤에 숨긴다 — 구현체(Supabase)를 갈아끼워도 호출부는 그대로다.
 */
public interface MirrorPublisher {

    /**
     * 스냅샷 1건을 전송한다.
     * 실패해도 예외를 던지지 않고 false를 반환한다 — 미러는 매매에 영향을 주면 안 된다.
     */
    boolean publish(MirrorSnapshot snapshot);
}
