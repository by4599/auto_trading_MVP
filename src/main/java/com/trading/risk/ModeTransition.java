package com.trading.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 운전 모드가 바뀐 사건 한 건 (2026-09-22 신설).
 *
 * <p>왜 필요한가: 2026-09-10부터 7거래일간 매매가 0건이었는데 아무도 몰랐다.
 * {@link TradingStatusManager}는 현재 모드를 {@code AtomicReference} 하나로만 들고 있어
 * <b>앱을 끄면 "언제 왜 멈췄나"가 통째로 사라진다</b>. 화면에서 되짚을 수 있도록 남긴다.
 *
 * <p><b>읽기 전용 기록이다</b> — 이 테이블은 아무 판정에도 쓰이지 않는다. 기록이 실패해도
 * 모드 전환은 이미 끝나 있다({@link ModeTransitionRecorder} 참고).
 *
 * <p>{@code reason}이 대체로 null인 이유: {@code changeMode(TradingMode)}는 새 모드만 받고
 * 사유를 받지 않는다. 사유를 얻으려면 9개 호출부(청산·SAFE_MODE 판정의 심장)를 고쳐야 하므로
 * <b>일부러 그대로 두었다.</b> 대신 호출부 클래스를 {@code source}에 남겨
 * "누가 바꿨나"는 알 수 있게 했다.
 */
@Entity
@Table(name = "mode_transition", indexes = {
        @Index(name = "idx_mode_transition_at", columnList = "occurred_at")
})
public class ModeTransition {

    /** 사유·출처 문자열 상한 — DB 컬럼 길이를 넘겨 INSERT가 깨지는 일이 없게 잘라 담는다 */
    static final int REASON_MAX = 500;
    static final int SOURCE_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_mode", nullable = false, length = 20)
    private TradingMode previousMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_mode", nullable = false, length = 20)
    private TradingMode newMode;

    /** 사유 — 지금은 거의 항상 null (위 클래스 주석) */
    @Column(name = "reason", length = REASON_MAX)
    private String reason;

    /** 트리거 출처 = changeMode를 부른 클래스 이름 (예: LiquidationService) */
    @Column(name = "source", length = SOURCE_MAX)
    private String source;

    protected ModeTransition() {}

    public static ModeTransition of(LocalDateTime occurredAt, TradingMode previousMode,
                                    TradingMode newMode, String reason, String source) {
        ModeTransition t = new ModeTransition();
        t.occurredAt    = occurredAt;
        t.previousMode  = previousMode;
        t.newMode       = newMode;
        t.reason        = clip(reason, REASON_MAX);
        t.source        = clip(source, SOURCE_MAX);
        return t;
    }

    private static String clip(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId()                  { return id; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public TradingMode getPreviousMode() { return previousMode; }
    public TradingMode getNewMode()      { return newMode; }
    public String getReason()            { return reason; }
    public String getSource()            { return source; }
}
