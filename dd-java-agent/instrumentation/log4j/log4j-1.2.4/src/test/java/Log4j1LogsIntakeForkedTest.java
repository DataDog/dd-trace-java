import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_LEVEL;
import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.logging.intake.LogsIntake;
import datadog.trace.api.logging.intake.LogsWriter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Each subclass runs in its own JVM with the agent installed for its configuration. */
@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "true")
@WithConfig(key = LOGS_INJECTION_ENABLED, value = "true")
@WithConfig(key = AGENTLESS_LOG_SUBMISSION_LEVEL, value = "WARN")
@WithConfig(key = SERVICE_NAME, value = "log4j1-service")
@WithConfig(key = ENV, value = "log4j1-env")
@WithConfig(key = VERSION, value = "1.0")
abstract class AbstractLog4j1LogsIntakeForkedTest extends AbstractInstrumentationTest {
  static final String LOGGER_NAME = "test.log4j1.intake";
  static final List<Map<String, Object>> MESSAGES = new CopyOnWriteArrayList<>();

  final Logger parent = Logger.getLogger(LOGGER_NAME);
  final Logger logger = Logger.getLogger(LOGGER_NAME + ".child");
  final ContextAppender appender = new ContextAppender();

  @BeforeAll
  static void registerWriter() {
    LogsIntake.registerWriter(new CapturingLogsWriter());
  }

  @AfterAll
  static void unregisterWriter() {
    LogsIntake.registerWriter(null);
  }

  @BeforeEach
  void setupLoggers() {
    MESSAGES.clear();
    clearMdc();
    parent.setAdditivity(false);
    parent.setLevel(Level.DEBUG);
    logger.setLevel(Level.DEBUG);
    logger.addAppender(appender);
  }

  @AfterEach
  void cleanupLoggers() {
    clearMdc();
    logger.removeAllAppenders();
    parent.removeAllAppenders();
  }

  static void clearMdc() {
    MDC.remove("custom");
    MDC.remove("dd.trace_id");
  }

  static boolean agentless() {
    return InstrumenterConfig.get().isAgentlessLogSubmissionEnabled();
  }

  static boolean injection() {
    return Config.get().isLogsInjectionEnabled();
  }

  static Map<String, String> serviceTags() {
    Map<String, String> tags = new HashMap<>();
    if (injection()) {
      tags.put(Tags.DD_SERVICE, "log4j1-service");
      tags.put(Tags.DD_ENV, "log4j1-env");
      tags.put(Tags.DD_VERSION, "1.0");
    }
    return tags;
  }

  @Test
  void submitsFilteredEventsWithLevelsAndExceptions() {
    logger.debug("debug");
    logger.info("info");
    logger.warn("warn");
    logger.error("error", new IllegalStateException("failure"));
    logger.fatal("fatal");
    logger.setLevel(Level.FATAL);
    logger.error("below logger level");

    assertEquals(5, appender.contexts.size());
    if (!agentless()) {
      assertTrue(MESSAGES.isEmpty());
      return;
    }
    assertEquals(asList("warn", "error", "fatal"), values("message"));
    assertEquals(asList("WARN", "ERROR", "FATAL"), values("level"));
    for (Map<String, Object> message : MESSAGES) {
      assertEquals(logger.getName(), message.get("loggerName"));
      assertEquals(Thread.currentThread().getName(), message.get("thread"));
      assertInstanceOf(Long.class, message.get("timestamp"));
    }
    Map<?, ?> thrown = (Map<?, ?>) MESSAGES.get(1).get("thrown");
    assertEquals(IllegalStateException.class.getName(), thrown.get("name"));
    assertEquals("failure", thrown.get("message"));
    assertTrue(
        thrown.get("extendedStackTrace").toString().contains("IllegalStateException: failure"));
    assertFalse(MESSAGES.get(0).containsKey("thrown"));
  }

  @Test
  void submitsProducerContextAndPreservesUserMdc() {
    AgentSpan span = AgentTracer.startSpan("test", "log4j1-producer");
    String traceId;
    String spanId;
    String childSpanId;
    try (ContextScope scope = AgentTracer.activateSpan(span)) {
      traceId = CorrelationIdentifier.getTraceId();
      spanId = CorrelationIdentifier.getSpanId();
      MDC.put("custom", "value");
      logger.warn("active");
      AgentSpan child = AgentTracer.startSpan("test", "log4j1-child");
      try (ContextScope childScope = AgentTracer.activateSpan(child)) {
        childSpanId = CorrelationIdentifier.getSpanId();
        logger.warn("child");
      } finally {
        child.finish();
      }
      MDC.put("dd.trace_id", "user-trace");
      logger.warn("collision");
      clearMdc();
    } finally {
      span.finish();
    }
    logger.warn("outside");

    Map<String, Object> active = appender.contexts.get(0);
    assertEquals(injection() ? traceId : null, active.get("dd.trace_id"));
    assertEquals(injection() ? spanId : null, active.get("dd.span_id"));
    assertEquals("value", active.get("custom"));
    if (!agentless()) {
      assertTrue(MESSAGES.isEmpty());
      return;
    }
    assertEquals(asList("active", "child", "collision", "outside"), values("message"));
    Map<String, String> expected = serviceTags();
    if (injection()) {
      expected.put("dd.trace_id", traceId);
      expected.put("dd.span_id", spanId);
    }
    expected.put("custom", "value");
    assertEquals(expected, contextMap(0));
    assertEquals(injection() ? traceId : null, contextMap(1).get("dd.trace_id"));
    assertEquals(injection() ? childSpanId : null, contextMap(1).get("dd.span_id"));
    assertEquals("user-trace", contextMap(2).get("dd.trace_id"));
    assertEquals(serviceTags(), contextMap(3));
  }

  @Test
  void submitsOnceWhenTheEventReachesParentAppenders() {
    ContextAppender parentAppender = new ContextAppender();
    parent.addAppender(parentAppender);

    logger.warn("propagated");

    assertEquals(1, appender.contexts.size());
    assertEquals(1, parentAppender.contexts.size());
    assertEquals(agentless() ? singletonList("propagated") : emptyList(), values("message"));
  }

  @Test
  void submitsOnceWithProducerContextWhenDeliveryIsAsynchronous() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch delivered = new CountDownLatch(1);
    ContextAppender asyncTarget = new ContextAppender();
    // AsyncAppender's dispatcher thread is globally ignored by the instrumentation test harness
    AppenderSkeleton async =
        new ContextAppender() {
          @Override
          protected void append(LoggingEvent event) {
            event.getMDCCopy();
            new Thread(
                    () -> {
                      try {
                        release.await(5, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      asyncTarget.doAppend(event);
                      delivered.countDown();
                    })
                .start();
          }
        };
    logger.removeAllAppenders();
    logger.addAppender(async);
    AgentSpan span = AgentTracer.startSpan("test", "log4j1-async");
    String traceId;
    String spanId;
    try (ContextScope scope = AgentTracer.activateSpan(span)) {
      traceId = CorrelationIdentifier.getTraceId();
      spanId = CorrelationIdentifier.getSpanId();
      logger.warn("async");
    } finally {
      span.finish();
    }
    release.countDown();

    assertTrue(delivered.await(5, TimeUnit.SECONDS));
    assertEquals(injection() ? spanId : null, asyncTarget.contexts.get(0).get("dd.span_id"));
    if (agentless()) {
      assertEquals(1, MESSAGES.size());
      assertEquals(injection() ? traceId : null, contextMap(0).get("dd.trace_id"));
      assertEquals(injection() ? spanId : null, contextMap(0).get("dd.span_id"));
    } else {
      assertTrue(MESSAGES.isEmpty());
    }
  }

  static List<Object> values(String key) {
    return MESSAGES.stream().map(message -> message.get(key)).collect(Collectors.toList());
  }

  static Map<?, ?> contextMap(int index) {
    return (Map<?, ?>) MESSAGES.get(index).get("contextMap");
  }

  /** Records the correlation context seen by Log4j 1 appenders. */
  static class ContextAppender extends AppenderSkeleton {
    final List<Map<String, Object>> contexts = new CopyOnWriteArrayList<>();

    @Override
    protected void append(LoggingEvent event) {
      Map<String, Object> context = new HashMap<>();
      context.put("dd.trace_id", event.getMDC("dd.trace_id"));
      context.put("dd.span_id", event.getMDC("dd.span_id"));
      context.put("custom", event.getMDC("custom"));
      contexts.add(context);
    }

    @Override
    public void close() {}

    @Override
    public boolean requiresLayout() {
      return false;
    }
  }

  static class CapturingLogsWriter implements LogsWriter {
    @Override
    public void log(Map<String, Object> message) {
      if (message.get("loggerName").toString().startsWith(LOGGER_NAME)) {
        MESSAGES.add(message);
      }
    }

    @Override
    public void start() {}

    @Override
    public void shutdown() {}
  }
}

class Log4j1LogsIntakeForkedTest extends AbstractLog4j1LogsIntakeForkedTest {}

@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "false")
class Log4j1NoSubmissionForkedTest extends AbstractLog4j1LogsIntakeForkedTest {}

@WithConfig(key = LOGS_INJECTION_ENABLED, value = "false")
class Log4j1NoInjectionForkedTest extends AbstractLog4j1LogsIntakeForkedTest {}

@WithConfig(key = TRACE_ENABLED, value = "false")
class Log4j1CiAgentlessForkedTest extends AbstractLog4j1LogsIntakeForkedTest {}

@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "false")
@WithConfig(key = TRACE_ENABLED, value = "false")
class Log4j1CiCorrelationForkedTest extends AbstractLog4j1LogsIntakeForkedTest {}
