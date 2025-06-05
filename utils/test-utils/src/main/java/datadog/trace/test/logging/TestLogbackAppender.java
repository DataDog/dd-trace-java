package datadog.trace.test.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Logback appender that captures logs for testing.
 *
 * <p>To set this up, add the following to your logback-test.xml:
 *
 * <pre>{@code
 * <configuration>
 *   <appender name="TEST" class="datadog.trace.test.logging.TestLogbackAppender" />
 *   <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder>
 *       <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
 *     </encoder>
 *   </appender>
 *   <root level="DEBUG">
 *     <appender-ref ref="TEST" />
 *     <appender-ref ref="STDOUT" />
 *   </root>
 * </configuration>
 * }</pre>
 */
public final class TestLogbackAppender extends AppenderBase<ILoggingEvent> {

  @Override
  protected void append(final ILoggingEvent event) {
    final CapturedLog log =
        new CapturedLog(
            event.getMarker(),
            event.getLevel().levelStr,
            event.getMessage(),
            event.getArgumentArray(),
            event.getFormattedMessage());
    TestLogCollector.INSTANCE.addLog(log);
  }
}
