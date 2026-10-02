import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_LEVEL;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
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
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.Level;
import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.Logger;
import org.jboss.logmanager.MDC;
import org.jboss.logmanager.handlers.AsyncHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Each subclass runs in its own JVM with the agent installed for its configuration. */
@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "true")
@WithConfig(key = LOGS_INJECTION_ENABLED, value = "true")
@WithConfig(key = AGENTLESS_LOG_SUBMISSION_LEVEL, value = "WARN")
abstract class AbstractJBossLogsIntakeForkedTest extends AbstractInstrumentationTest {
  static final String LOGGER_NAME = "test.jboss.intake";
  static final List<Map<String, Object>> MESSAGES = new CopyOnWriteArrayList<>();
  // The JBoss LogManager is not the JUL log manager of the test JVM
  static final LogContext LOG_CONTEXT = LogContext.create();

  final Logger parent = LOG_CONTEXT.getLogger(LOGGER_NAME);
  final Logger logger = LOG_CONTEXT.getLogger(LOGGER_NAME + ".child");
  final ContextHandler handler = new ContextHandler();

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
    MDC.clear();
    parent.setUseParentHandlers(false);
    parent.setLevel(Level.TRACE);
    logger.setLevel(Level.TRACE);
    logger.addHandler(handler);
  }

  @AfterEach
  void cleanupLoggers() {
    MDC.clear();
    logger.removeHandler(handler);
    parent.clearHandlers();
  }

  static boolean agentless() {
    return InstrumenterConfig.get().isAgentlessLogSubmissionEnabled();
  }

  static boolean injection() {
    return Config.get().isLogsInjectionEnabled();
  }

  @Test
  void submitsFilteredEventsWithMappedLevelsAndExceptions() {
    logger.log(Level.DEBUG, "debug");
    logger.log(Level.INFO, "info");
    logger.log(Level.WARN, "warn");
    logger.log(java.util.logging.Level.WARNING, "jul {0}", "warning");
    logger.log(Level.ERROR, "error", new IllegalStateException("failure"));
    logger.log(Level.FATAL, "fatal");

    assertEquals(6, handler.contexts.size());
    if (!agentless()) {
      assertTrue(MESSAGES.isEmpty());
      return;
    }
    assertEquals(asList("warn", "jul warning", "error", "fatal"), values("message"));
    assertEquals(asList("WARN", "WARN", "ERROR", "FATAL"), values("level"));
    for (Map<String, Object> message : MESSAGES) {
      assertEquals(logger.getName(), message.get("loggerName"));
      assertEquals(Thread.currentThread().getName(), message.get("thread"));
      assertInstanceOf(Long.class, message.get("timestamp"));
    }
    Map<?, ?> thrown = (Map<?, ?>) MESSAGES.get(2).get("thrown");
    assertEquals(IllegalStateException.class.getName(), thrown.get("name"));
    assertEquals("failure", thrown.get("message"));
    assertTrue(
        thrown.get("extendedStackTrace").toString().contains("IllegalStateException: failure"));
    assertFalse(MESSAGES.get(0).containsKey("thrown"));
  }

  @Test
  void submitsProducerContextAndPreservesUserMdc() {
    AgentSpan span = AgentTracer.startSpan("test", "jboss-producer");
    String traceId;
    String spanId;
    String childSpanId;
    try (ContextScope scope = AgentTracer.activateSpan(span)) {
      traceId = CorrelationIdentifier.getTraceId();
      spanId = CorrelationIdentifier.getSpanId();
      MDC.put("custom", "value");
      logger.log(Level.WARN, "active");
      AgentSpan child = AgentTracer.startSpan("test", "jboss-child");
      try (ContextScope childScope = AgentTracer.activateSpan(child)) {
        childSpanId = CorrelationIdentifier.getSpanId();
        logger.log(Level.WARN, "child");
      } finally {
        child.finish();
      }
      MDC.put("dd.trace_id", "user-trace");
      logger.log(Level.WARN, "collision");
      MDC.clear();
    } finally {
      span.finish();
    }
    logger.log(Level.WARN, "outside");

    Map<String, String> active = handler.contexts.get(0);
    assertEquals(injection() ? traceId : null, active.get("dd.trace_id"));
    assertEquals(injection() ? spanId : null, active.get("dd.span_id"));
    assertEquals("value", active.get("custom"));
    if (!agentless()) {
      assertTrue(MESSAGES.isEmpty());
      return;
    }
    assertEquals(asList("active", "child", "collision", "outside"), values("message"));
    Map<String, String> expected = new HashMap<>();
    if (injection()) {
      expected.put("dd.trace_id", traceId);
      expected.put("dd.span_id", spanId);
    }
    expected.put("custom", "value");
    assertEquals(expected, contextMap(0));
    assertEquals(injection() ? traceId : null, contextMap(1).get("dd.trace_id"));
    assertEquals(injection() ? childSpanId : null, contextMap(1).get("dd.span_id"));
    assertEquals("user-trace", contextMap(2).get("dd.trace_id"));
    assertEquals(emptyMap(), contextMap(3));
  }

  @Test
  void submitsOnceWhenTheEventReachesParentHandlers() {
    ContextHandler parentHandler = new ContextHandler();
    parent.addHandler(parentHandler);

    logger.log(Level.WARN, "propagated");

    assertEquals(1, handler.contexts.size());
    assertEquals(1, parentHandler.contexts.size());
    assertEquals(agentless() ? singletonList("propagated") : emptyList(), values("message"));
  }

  @Test
  void submitsOnceWithProducerContextWhenDeliveryIsAsynchronous() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch delivered = new CountDownLatch(1);
    ContextHandler asyncTarget =
        new ContextHandler() {
          @Override
          public void publish(LogRecord record) {
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            super.publish(record);
            delivered.countDown();
          }
        };
    AsyncHandler async = new AsyncHandler();
    async.addHandler(asyncTarget);
    logger.removeHandler(handler);
    logger.addHandler(async);
    try {
      AgentSpan span = AgentTracer.startSpan("test", "jboss-async");
      String traceId;
      String spanId;
      try (ContextScope scope = AgentTracer.activateSpan(span)) {
        traceId = CorrelationIdentifier.getTraceId();
        spanId = CorrelationIdentifier.getSpanId();
        logger.log(Level.WARN, "async");
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
    } finally {
      logger.removeHandler(async);
      async.close();
    }
  }

  static List<Object> values(String key) {
    return MESSAGES.stream().map(message -> message.get(key)).collect(Collectors.toList());
  }

  static Map<?, ?> contextMap(int index) {
    return (Map<?, ?>) MESSAGES.get(index).get("contextMap");
  }

  /** Records the correlation context seen by JBoss handlers. */
  static class ContextHandler extends Handler {
    final List<Map<String, String>> contexts = new CopyOnWriteArrayList<>();

    @Override
    public void publish(LogRecord record) {
      ExtLogRecord extRecord = (ExtLogRecord) record;
      Map<String, String> context = new HashMap<>();
      context.put("dd.trace_id", extRecord.getMdc("dd.trace_id"));
      context.put("dd.span_id", extRecord.getMdc("dd.span_id"));
      context.put("custom", extRecord.getMdc("custom"));
      contexts.add(context);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
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

class JBossLogsIntakeForkedTest extends AbstractJBossLogsIntakeForkedTest {}

@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "false")
class JBossNoSubmissionForkedTest extends AbstractJBossLogsIntakeForkedTest {}

@WithConfig(key = LOGS_INJECTION_ENABLED, value = "false")
class JBossNoInjectionForkedTest extends AbstractJBossLogsIntakeForkedTest {}

@WithConfig(key = TRACE_ENABLED, value = "false")
class JBossCiAgentlessForkedTest extends AbstractJBossLogsIntakeForkedTest {}

@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "false")
@WithConfig(key = TRACE_ENABLED, value = "false")
class JBossCiCorrelationForkedTest extends AbstractJBossLogsIntakeForkedTest {}
