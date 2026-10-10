package com.jimeng.dataserver.ai.skill;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/** 测试里抓某个类的日志（Logback 的 ListAppender）；用完要 close，把 appender 摘掉。 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Logger logger) {
        this.logger = logger;
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> type) {
        return new LogCapture((Logger) LoggerFactory.getLogger(type));
    }

    /** 抓所有类的日志（挂在根 logger 上）：要断言「整条流程里哪儿都没打出某个串」「一条 ERROR 都没有」时用。 */
    public static LogCapture everything() {
        return new LogCapture((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME));
    }

    /** 某个级别的日志（已经格式化好的整句）。 */
    public List<String> at(Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    public List<String> warnings() {
        return at(Level.WARN);
    }

    public List<String> errors() {
        return at(Level.ERROR);
    }

    /** 所有级别的日志整句。 */
    public List<String> all() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
