import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_LEVEL;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import datadog.trace.instrumentation.tinylog2.ContextWriter;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tinylog.Logger;
import org.tinylog.ThreadContext;

/** Each subclass runs in its own JVM with the agent installed for its configuration. */
@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "true")
@WithConfig(key = LOGS_INJECTION_ENABLED, value = "true")
@WithConfig(key = AGENTLESS_LOG_SUBMISSION_LEVEL, value = "WARN")
@WithConfig(key = "tinylog.level", value = "trace", addPrefix = false)
@WithConfig(key = "tinylog.writingthread", value = "false", addPrefix = false)
@WithConfig(
    key = "tinylog.writer1",
    value = "datadog.trace.instrumentation.tinylog2.ContextWriter",
    addPrefix = false)
@WithConfig(
    key = "tinylog.writer2",
    value = "datadog.trace.instrumentation.tinylog2.ContextWriter",
    addPrefix = false)
abstract class AbstractTinylogLogsIntakeForkedTest extends AbstractInstrumentationTest {
  static final List<Map<String, Object>> MESSAGES = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void registerWriter() {
    LogsIntake.registerWriter(new CapturingLogsWriter());
  }

  @AfterAll
  static void unregisterWriter() {
    LogsIntake.registerWriter(null);
  }

  @BeforeEach
  void clearState() {
    MESSAGES.clear();
    ContextWriter.CONTEXTS.clear();
    ThreadContext.clear();
  }

  @AfterEach
  void clearContext() {
    ThreadContext.clear();
  }

  static boolean agentless() {
    return InstrumenterConfig.get().isAgentlessLogSubmissionEnabled();
  }

  static boolean injection() {
    return Config.get().isLogsInjectionEnabled();
  }

  static boolean writingThread() {
    return Boolean.getBoolean("tinylog.writingthread");
  }

  /** Waits for both writers to receive the entries and returns the contexts of the first one. */
  static List<Map<String, String>> writtenContexts(int count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (ContextWriter.CONTEXTS.size() < count * 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(count * 2, ContextWriter.CONTEXTS.size());
    String producer = Thread.currentThread().getName();
    for (Map<String, String> context : ContextWriter.CONTEXTS) {
      assertEquals(writingThread(), !producer.equals(context.get("thread")));
    }
    return ContextWriter.CONTEXTS.stream()
        .filter(context -> "0".equals(context.get("writer")))
        .collect(Collectors.toList());
  }

  @Test
  void submitsFilteredEventsOnceWithLevelsTagsAndExceptions() throws Exception {
    Logger.debug("debug");
    Logger.info("info");
    Logger.warn("warn {}", "formatted");
    Logger.tag("intake").warn("tagged");
    Logger.error(new IllegalStateException("failure"), "error");

    assertEquals(5, writtenContexts(5).size());
    if (!agentless()) {
      assertTrue(MESSAGES.isEmpty());
      return;
    }
    assertEquals(asList("warn formatted", "tagged", "error"), values("message"));
    assertEquals(asList("WARN", "WARN", "ERROR"), values("level"));
    assertEquals(asList(null, "intake", null), values("loggerName"));
    for (Map<String, Object> message : MESSAGES) {
      assertEquals(Thread.currentThread().getName(), message.get("thread"));
      assertInstanceOf(Long.class, message.get("timestamp"));
    }
    Map<?, ?> thrown = (Map<?, ?>) MESSAGES.get(2).get("thrown");
    assertEquals(IllegalStateException.class.getName(), thrown.get("name"));
    assertEquals("failure", thrown.get("message"));
    assertTrue(
        thrown.get("extendedStackTrace").toString().contains("IllegalStateException: failure"));
  }

  @Test
  void submitsProducerContextAndPreservesUserContext() throws Exception {
    AgentSpan span = AgentTracer.startSpan("test", "tinylog-producer");
    String traceId;
    String spanId;
    String childSpanId;
    try (ContextScope scope = AgentTracer.activateSpan(span)) {
      traceId = CorrelationIdentifier.getTraceId();
      spanId = CorrelationIdentifier.getSpanId();
      ThreadContext.put("custom", "value");
      Logger.warn("active");
      AgentSpan child = AgentTracer.startSpan("test", "tinylog-child");
      try (ContextScope childScope = AgentTracer.activateSpan(child)) {
        childSpanId = CorrelationIdentifier.getSpanId();
        Logger.warn("child");
      } finally {
        child.finish();
      }
      ThreadContext.put("dd.trace_id", "user-trace");
      Logger.warn("collision");
      ThreadContext.clear();
    } finally {
      span.finish();
    }
    Logger.warn("outside");
    List<Map<String, String>> contexts = writtenContexts(4);

    assertEquals(injection() ? traceId : null, contexts.get(0).get("dd.trace_id"));
    assertEquals(injection() ? spanId : null, contexts.get(0).get("dd.span_id"));
    assertEquals("value", contexts.get(0).get("custom"));
    assertEquals(injection() ? childSpanId : null, contexts.get(1).get("dd.span_id"));
    assertNull(contexts.get(3).get("dd.trace_id"));
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

  static List<Object> values(String key) {
    return MESSAGES.stream().map(message -> message.get(key)).collect(Collectors.toList());
  }

  static Map<?, ?> contextMap(int index) {
    return (Map<?, ?>) MESSAGES.get(index).get("contextMap");
  }

  static class CapturingLogsWriter implements LogsWriter {
    @Override
    public void log(Map<String, Object> message) {
      MESSAGES.add(message);
    }

    @Override
    public void start() {}

    @Override
    public void shutdown() {}
  }
}

class TinylogLogsIntakeForkedTest extends AbstractTinylogLogsIntakeForkedTest {}

@WithConfig(key = "tinylog.writingthread", value = "true", addPrefix = false)
class TinylogWritingThreadForkedTest extends AbstractTinylogLogsIntakeForkedTest {}

@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "false")
class TinylogNoSubmissionForkedTest extends AbstractTinylogLogsIntakeForkedTest {}

@WithConfig(key = LOGS_INJECTION_ENABLED, value = "false")
class TinylogNoInjectionForkedTest extends AbstractTinylogLogsIntakeForkedTest {}

@WithConfig(key = TRACE_ENABLED, value = "false")
class TinylogCiAgentlessForkedTest extends AbstractTinylogLogsIntakeForkedTest {}

@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "false")
@WithConfig(key = TRACE_ENABLED, value = "false")
class TinylogCiCorrelationForkedTest extends AbstractTinylogLogsIntakeForkedTest {}
