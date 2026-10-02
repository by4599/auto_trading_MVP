package com.trading.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 모드 전환 한 건을 DB에 남긴다 (2026-09-22 신설).
 *
 * <p><b>이 클래스는 아무것도 판정하지 않는다.</b> {@link TradingStatusManager}가 모드를
 * 이미 바꾼 뒤에 불리고, 여기서 무슨 일이 나도 모드는 되돌아가지 않는다.
 *
 * <p>안전 설계 세 가지:
 * <ul>
 *   <li><b>예외를 전부 삼킨다</b> — DB가 죽어도 모드 전환이 실패하면 안 된다.</li>
 *   <li><b>저장은 {@link ModeTransitionWriter}(별도 빈, {@code REQUIRES_NEW})에 맡긴다</b> —
 *       {@code changeMode}는 <b>남의 트랜잭션 안에서도 불린다.</b> 예: {@code StopLossArmer}가
 *       트랜잭션 안에서 KIS를 호출하고, 그 실패가 SAFE_MODE 전환을 부른다. 그냥 저장하면
 *       INSERT가 그 트랜잭션에 합류해, 실패 시 <b>방금 장착한 손절선까지 롤백된다.</b>
 *       별도 빈으로 떼어야 프록시를 타 {@code REQUIRES_NEW}가 실제로 걸리고, 커밋/롤백이
 *       그 안에서 끝나 예외가 아래 try/catch로 올라온다 (감사 24_audit M-1).</li>
 *   <li><b>{@code @Profile("!backtest")}</b> — 백테스트는 {@code BacktestStateReset}이
 *       모드를 만지므로, 빈 자체를 만들지 않아 시뮬 DB·결정성에 손대지 않는다.</li>
 * </ul>
 *
 * <p>별도 스레드로 빼지 않은 이유: 로컬 H2 단일 INSERT(수 ms)이고, 같은 스케줄러 스레드에서
 * 도는 {@code ShadowPortfolio.tick()}·{@code DailyPnlRecorder}가 이미 같은 방식으로 쓴다.
 * 감사(20_audit M-2)가 지적한 것은 최대 8초 걸리는 <b>동기 HTTP</b>였지 DB 쓰기가 아니다.
 */
@Component
@Profile("!backtest")
public class ModeTransitionRecorder {

    private static final Logger log = LoggerFactory.getLogger(ModeTransitionRecorder.class);

    /** 우리 코드만 출처 후보로 삼는다 (스프링·JDK 프레임 제외) */
    private static final String APP_PACKAGE = "com.trading.";

    private final ModeTransitionWriter writer;
    private final Clock clock;

    public ModeTransitionRecorder(ModeTransitionWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    /**
     * 전환 한 건 기록. 사유는 호출부가 주는 것이 없으면 null이다 ({@link ModeTransition} 주석).
     * 어떤 예외도 밖으로 내보내지 않는다.
     */
    public void record(TradingMode previous, TradingMode next, String reason) {
        try {
            writer.saveInNewTransaction(ModeTransition.of(
                    LocalDateTime.now(clock), previous, next, reason, callerClassName()));
        } catch (Exception e) {
            // 기록은 부가 기능이다 — 실패해도 모드 전환은 이미 끝났다
            log.warn("[ModeTransition] 기록 실패 (모드 전환 자체는 정상 완료) — {} → {}: {}",
                    previous, next, e.toString());
        }
    }

    /**
     * changeMode를 부른 바깥 클래스 이름. 사유 문자열이 없는 지금, "누가 바꿨나"를 알려주는
     * 유일한 단서다 (LiquidationService / KisApiClient / TradingController / ...).
     * 모드 전환은 하루 몇 번뿐이라 스택 훑기 비용은 문제가 되지 않는다.
     */
    private static String callerClassName() {
        return StackWalker.getInstance()
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getClassName)
                        .filter(ModeTransitionRecorder::isTriggerCandidate)
                        .findFirst())
                .map(ModeTransitionRecorder::simpleName)
                .orElse(null);
    }

    /** 사이에 끼어드는 프레임(스프링 프록시·리플렉션)을 걸러내고 우리 코드만 남긴다 */
    private static boolean isTriggerCandidate(String className) {
        return className.startsWith(APP_PACKAGE)
                && !isSameClass(className, ModeTransitionRecorder.class)
                && !isSameClass(className, TradingStatusManager.class);
    }

    /**
     * 같은 클래스인가. <b>단순 startsWith를 쓰면 안 된다</b> —
     * "TradingStatusManagerTest"가 "TradingStatusManager"로 시작해서 함께 걸러진다.
     * 프록시({@code Foo$$SpringCGLIB$$0})만 같은 클래스로 본다.
     */
    private static boolean isSameClass(String className, Class<?> type) {
        return className.equals(type.getName()) || className.startsWith(type.getName() + "$$");
    }

    /** com.trading.risk.LiquidationService$$SpringCGLIB$$0 → LiquidationService */
    private static String simpleName(String className) {
        String withoutPackage = className.substring(className.lastIndexOf('.') + 1);
        int proxyMark = withoutPackage.indexOf("$$");
        return proxyMark < 0 ? withoutPackage : withoutPackage.substring(0, proxyMark);
    }
}
