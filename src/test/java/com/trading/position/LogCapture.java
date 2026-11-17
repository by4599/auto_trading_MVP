package com.trading.position;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 테스트 전용 — 지정한 로거(와 그 하위 로거)가 남긴 로그를 붙잡는다. try-with-resources로 쓴다.
 *
 * <p>로그 자체가 이번 변경의 산출물이다(결함 6 — 원래 숫자 기록·경고 횟수 제한). 그래서 "남겼는가 / 몇 줄인가 /
 * 무엇이 빠졌는가(비밀값)"를 실제 로그 이벤트로 확인한다.
 */
final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(String loggerName) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerName);
        appender.start();
        logger.addAppender(appender);
    }

    static LogCapture of(Class<?> type) {
        return new LogCapture(type.getName());
    }

    /** 패키지 이름을 주면 그 아래 모든 클래스의 로그가 잡힌다(로거 상속) */
    static LogCapture of(String loggerName) {
        return new LogCapture(loggerName);
    }

    /** 지정 수준의 메시지(인자 치환 완료)만 순서대로 */
    List<String> messages(Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 수준과 무관하게 전부 */
    List<String> allMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
