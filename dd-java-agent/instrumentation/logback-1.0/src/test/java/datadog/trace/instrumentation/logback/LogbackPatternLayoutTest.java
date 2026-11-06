package datadog.trace.instrumentation.logback;

import static datadog.trace.api.CorrelationIdentifier.getSpanId;
import static datadog.trace.api.CorrelationIdentifier.getTraceId;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.AppenderBase;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.tabletest.junit.TableTest;

@WithConfig(key = "service", value = "pattern-test")
@WithConfig(key = "logs.pattern.inject", value = "true")
@WithConfig(key = "logs.injection.enabled", value = "true")
class LogbackPatternLayoutTest extends AbstractInstrumentationTest {
  private static final String SPAN = "dd.span_id=%X{dd.span_id} ";
  private static final String TRACE = "dd.trace_id=%X{dd.trace_id} ";
  private static final String SERVICE = "dd.service=%X{dd.service} ";

  private LogCapture logs;

  @BeforeEach
  void setupLogger() {
    MDC.clear();
    logs = new LogCapture();
  }

  @AfterEach
  void cleanupLogger() {
    logs.logger.detachAndStopAllAppenders();
    MDC.clear();
  }

  @TableTest({
    "Scenario       | Template                                              | Trace | Service | Span ",
    "plain          | %level @%logger - %msg%n                              | true  | true    | true ",
    "trace only     | %X{dd.trace_id} @%msg%n                               | false | true    | true ",
    "service only   | %mdc{dd.service} @%msg%n                              | true  | false   | true ",
    "both           | %X{dd.trace_id} @%mdc{dd.service} %msg%n              | false | false   | true ",
    "all MDC        | %X @%msg%n                                            | false | false   | false",
    "empty options  | %mdc{} @%msg%n                                        | false | false   | false",
    "empty MDC key  | %X{:-NA} @%msg%n                                      | true  | true    | true ",
    "default values | %X{dd.trace_id:-NA} @%mdc{dd.service:-NA} %msg%n      | false | false   | true ",
    "formatted      | %-32X{dd.trace_id} @%20mdc{dd.service} %msg%n         | false | false   | true ",
    "nested         | %replace(%X{dd.trace_id}){a,b} @%msg%n                | false | true    | true ",
    "escaped        | \\%X{dd.trace_id} @%msg%n                             | true  | true    | true ",
    "literal keys   | dd.trace_id @dd.service %msg%n                        | true  | true    | true ",
    "span only      | %X{dd.span_id} @%msg%n                                | true  | true    | false",
    "all fields     | %X{dd.service} @%X{dd.trace_id} %X{dd.span_id} %msg%n | false | false   | false",
    "other MDC      | %X{business.key} @%msg%n                              | true  | true    | true "
  })
  void addsOnlyMissingFieldsAndDoesNotDuplicateOnRestart(
      String template, boolean trace, boolean service, boolean span) {
    PatternLayout layout = logs.newLayout(template.replace("@", ""));
    String expected =
        template.replace("@", (service ? SERVICE : "") + (trace ? TRACE : "") + (span ? SPAN : ""));
    assertEquals(expected, layout.getPattern());
    assertTrue(layout.isStarted());

    layout.stop();
    layout.start();
    assertEquals(expected, layout.getPattern());
  }

  @Test
  void converterOptionsAreNotMistakenForMdcConverters() {
    String pattern = "%replace(%msg){'message','%X{dd.trace_id} %X{dd.service}'}%n";
    assertEquals(
        "%replace(%msg){'message','%X{dd.trace_id} %X{dd.service}'} "
            + (SERVICE + TRACE + SPAN).trim()
            + "%n",
        logs.newLayout(pattern).getPattern());
  }

  @TableTest({
    "Scenario    | First field                        ",
    "date spaces | %d{yyyy-MM-dd HH:mm:ss.SSS,UTC}    ",
    "date alias  | %date{yyyy-MM-dd HH:mm:ss.SSS,UTC} ",
    "brackets    | '[%d{yyyy-MM-dd HH:mm:ss.SSS,UTC}]'",
    "composite   | %replace(%d{HH:mm:ss,UTC}){'a','b'}",
    "color       | %cyan(%d{HH:mm:ss,UTC})            ",
    "grouped     | %(%d{HH:mm:ss,UTC} %level)         "
  })
  void keepsEntireDateFieldBeforeCorrelationFields(String firstField) {
    String pattern = firstField + " [%thread] %msg%n";
    PatternLayout layout = logs.newLayout(pattern);
    assertTrue(layout.isStarted());
    assertEquals(
        firstField + " " + SERVICE + TRACE + SPAN + "[%thread] %msg%n", layout.getPattern());
  }

  @Test
  @WithConfig(key = "logs.pattern.inject", value = "false")
  void keepsOriginalPatternWhenDisabled() {
    assertEquals("%msg%n", logs.newLayout("%msg%n").getPattern());
  }

  @Test
  void asyncAppenderFormatsOriginatingTraceIdAndService() throws InterruptedException {
    PatternLayout layout = logs.newLayout("%msg%n");
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<String> output = new AtomicReference<>();
    AppenderBase<ILoggingEvent> consumer =
        new AppenderBase<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent event) {
            output.set(layout.doLayout(event));
            received.countDown();
          }
        };
    consumer.setContext(logs.context);
    consumer.start();
    AsyncAppender async = new AsyncAppender();
    async.setContext(logs.context);
    async.addAppender(consumer);
    async.start();
    logs.logger.addAppender(async);

    AgentSpan span = startSpan("test", "request");
    String traceId;
    String spanId;
    try {
      try (ContextScope ignored = activateSpan(span)) {
        traceId = getTraceId();
        spanId = getSpanId();
        logs.logger.info("async message");
      } finally {
        span.finish();
      }
      assertTrue(received.await(10, TimeUnit.SECONDS), "async appender did not receive the log");
      assertEquals(
          "async message dd.service=pattern-test dd.trace_id="
              + traceId
              + " dd.span_id="
              + spanId
              + System.lineSeparator(),
          output.get());
    } finally {
      async.stop();
      consumer.stop();
    }
  }

  @Test
  void doesNotInventIdsWithoutActiveSpan() {
    assertEquals(
        "background dd.service=pattern-test dd.trace_id= dd.span_id=" + System.lineSeparator(),
        logs.newLayout("%msg%n").doLayout(logs.event("background")));
  }

  @Test
  void preservesAnApplicationProvidedServiceName() {
    MDC.put("dd.service", "application-service");
    assertEquals(
        "message dd.service=application-service dd.trace_id= dd.span_id=" + System.lineSeparator(),
        logs.newLayout("%msg%n").doLayout(logs.event("message")));
  }

  private static class LogCapture {
    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private final Logger logger = context.getLogger(LogbackPatternLayoutTest.class);

    private LogCapture() {
      logger.setLevel(Level.DEBUG);
      logger.setAdditive(false);
    }

    private LoggingEvent event(String message) {
      return new LoggingEvent(getClass().getName(), logger, Level.INFO, message, null, null);
    }

    private PatternLayout newLayout(String pattern) {
      PatternLayout layout = new PatternLayout();
      layout.setContext(context);
      layout.setPattern(pattern);
      layout.start();
      return layout;
    }
  }
}

@WithConfig(key = "logs.pattern.inject", value = "true")
@WithConfig(key = "logs.injection.enabled", value = "false")
class LogbackPatternLayoutLogsInjectionDisabledTest extends AbstractInstrumentationTest {
  @Test
  void doesNotInventIdsWhenLogsInjectionIsDisabled() {
    MDC.clear();
    Logger logger = (Logger) LoggerFactory.getLogger(getClass());
    PatternLayout layout = new PatternLayout();
    layout.setContext(logger.getLoggerContext());
    layout.setPattern("%msg%n");
    layout.start();

    AgentSpan span = startSpan("test", "request");
    try (ContextScope ignored = activateSpan(span)) {
      LoggingEvent event =
          new LoggingEvent(getClass().getName(), logger, Level.INFO, "message", null, null);
      assertEquals(
          "message dd.service= dd.trace_id= dd.span_id=" + System.lineSeparator(),
          layout.doLayout(event));
    } finally {
      span.finish();
      layout.stop();
      MDC.clear();
    }
  }
}
