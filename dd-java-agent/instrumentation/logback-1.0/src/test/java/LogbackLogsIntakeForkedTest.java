import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_ENABLED;
import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_LEVEL;
import static datadog.trace.api.config.GeneralConfig.APP_LOGS_COLLECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Each subclass runs in its own JVM with the agent installed for its configuration. */
@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "true")
@WithConfig(key = APP_LOGS_COLLECTION_ENABLED, value = "false")
@WithConfig(key = LOGS_INJECTION_ENABLED, value = "true")
@WithConfig(key = "integration.logback.enabled", value = "true")
@WithConfig(key = AGENTLESS_LOG_SUBMISSION_LEVEL, value = "WARN")
abstract class AbstractLogbackLogsIntakeForkedTest extends AbstractInstrumentationTest {
  static final String LOGGER_NAME = "test.logback.intake";
  static final List<Map<String, Object>> MESSAGES = new CopyOnWriteArrayList<>();

  static LogbackApplication app;

  @BeforeAll
  static void setupApplication() throws Exception {
    app =
        (LogbackApplication)
            new LogbackTestClassLoader(AbstractLogbackLogsIntakeForkedTest.class.getClassLoader())
                .loadClass("LogbackTestApplication")
                .getDeclaredConstructor()
                .newInstance();
    LogsIntake.registerWriter(new CapturingLogsWriter());
  }

  @AfterAll
  static void unregisterWriter() {
    LogsIntake.registerWriter(null);
  }

  @BeforeEach
  void clearState() {
    MESSAGES.clear();
    app.clear();
    app.getContexts().clear();
  }

  @AfterEach
  void clearMdc() {
    app.clear();
  }

  static boolean agentless() {
    return InstrumenterConfig.get().isAgentlessLogSubmissionEnabled();
  }

  static boolean appLogs() {
    return InstrumenterConfig.get().isAppLogsCollectionEnabled();
  }

  static boolean injection() {
    return Config.get().isLogsInjectionEnabled();
  }

  static boolean integration() {
    return InstrumenterConfig.get().isIntegrationEnabled(singletonList("logback"), true);
  }

  static boolean fatalLevel() {
    return "FATAL".equals(Config.get().getAgentlessLogSubmissionLevel());
  }

  static boolean collected() {
    return integration() && (appLogs() || (agentless() && !fatalLevel()));
  }

  @Test
  void flagsPreserveOptInAppLogPrecedenceAndFrameworkFiltering() {
    app.log("DEBUG", "debug");
    app.log("INFO", "info");
    app.log("WARN", "formatted message");
    app.log("ERROR", "error");

    List<String> expected;
    if (!collected()) {
      expected = emptyList();
    } else if (appLogs()) {
      expected = asList("debug", "info", "formatted message", "error");
    } else {
      expected = asList("formatted message", "error");
    }
    assertEquals(expected, messages());
    if (!MESSAGES.isEmpty()) {
      Map<?, ?> thrown = (Map<?, ?>) MESSAGES.get(MESSAGES.size() - 1).get("thrown");
      assertEquals(IllegalStateException.class.getName(), thrown.get("name"));
      assertEquals("failure", thrown.get("message"));
      assertFalse(thrown.get("extendedStackTrace").toString().isEmpty());
      for (Map<String, Object> message : MESSAGES) {
        assertEquals(!appLogs(), message.containsKey("contextMap"));
        assertEquals(!appLogs(), message.containsKey("timestamp"));
      }
    }
  }

  @Test
  void producerCorrelationHonorsInjectionSwitchAndSnapshotsMdcBeforeWrapperCleanup() {
    AgentSpan span = AgentTracer.startSpan("test", "logback-producer");
    String traceId;
    String spanId;
    try (ContextScope scope = AgentTracer.activateSpan(span)) {
      traceId = CorrelationIdentifier.getTraceId();
      spanId = CorrelationIdentifier.getSpanId();
      app.put("custom", "before-clear");
      app.log("WARN", "active");
      app.put("dd.trace_id", "user-trace");
      app.log("WARN", "collision");
      app.clear();
    } finally {
      span.finish();
    }
    app.log("WARN", "outside");

    List<Map<String, String>> contexts = app.getContexts();
    assertEquals(3, contexts.size());
    boolean injected = integration() && injection();
    assertEquals(injected ? traceId : null, contexts.get(0).get("dd.trace_id"));
    assertEquals(injected ? spanId : null, contexts.get(0).get("dd.span_id"));
    assertEquals("user-trace", contexts.get(1).get("dd.trace_id"));
    assertFalse(contexts.get(2).containsKey("dd.trace_id"));
    if (!collected()) {
      assertTrue(MESSAGES.isEmpty());
      return;
    }
    assertEquals(3, MESSAGES.size());
    if (appLogs()) {
      assertEquals(traceId, MESSAGES.get(0).get("dd.trace_id"));
      assertEquals(spanId, MESSAGES.get(0).get("dd.span_id"));
      assertEquals(traceId, MESSAGES.get(1).get("dd.trace_id"));
      assertFalse(MESSAGES.get(2).containsKey("dd.trace_id"));
    } else {
      Map<?, ?> first = contextMap(0);
      assertEquals(injection() ? traceId : null, first.get("dd.trace_id"));
      assertEquals(injection() ? spanId : null, first.get("dd.span_id"));
      assertEquals("before-clear", first.get("custom"));
      assertEquals("user-trace", contextMap(1).get("dd.trace_id"));
      assertFalse(contextMap(2).containsKey("dd.trace_id"));
    }
  }

  static List<String> messages() {
    return MESSAGES.stream().map(m -> (String) m.get("message")).collect(Collectors.toList());
  }

  static Map<?, ?> contextMap(int index) {
    return (Map<?, ?>) MESSAGES.get(index).get("contextMap");
  }

  static class CapturingLogsWriter implements LogsWriter {
    @Override
    public void log(Map<String, Object> message) {
      if (LOGGER_NAME.equals(message.get("loggerName"))) {
        MESSAGES.add(message);
      }
    }

    @Override
    public void start() {}

    @Override
    public void shutdown() {}
  }
}

class LogbackLogsIntakeForkedTest extends AbstractLogbackLogsIntakeForkedTest {
  @Test
  void asyncDeliveryAfterScopeClosesRetainsProducerIdsExactlyOnce() throws Exception {
    app.startAsync();
    AgentSpan span = AgentTracer.startSpan("test", "async-producer");
    String traceId;
    String spanId;
    try (ContextScope scope = AgentTracer.activateSpan(span)) {
      traceId = CorrelationIdentifier.getTraceId();
      spanId = CorrelationIdentifier.getSpanId();
      app.log("WARN", "async");
    } finally {
      span.finish();
    }
    Map<String, String> deliveredContext = app.finishAsync();

    assertEquals(1, MESSAGES.size());
    assertEquals(traceId, contextMap(0).get("dd.trace_id"));
    assertEquals(spanId, contextMap(0).get("dd.span_id"));
    assertEquals(spanId, deliveredContext.get("dd.span_id"));
  }

  @Test
  void julBridgeReachesTheSameBackendHookOnce() {
    app.jul();

    assertEquals(singletonList("bridged"), messages());
  }
}

@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "false")
class LogbackNoSubmissionForkedTest extends AbstractLogbackLogsIntakeForkedTest {}

@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "false")
@WithConfig(key = APP_LOGS_COLLECTION_ENABLED, value = "true")
class LogbackAppLogsForkedTest extends AbstractLogbackLogsIntakeForkedTest {}

@WithConfig(key = APP_LOGS_COLLECTION_ENABLED, value = "true")
class LogbackBothFlagsForkedTest extends AbstractLogbackLogsIntakeForkedTest {}

@WithConfig(key = AGENTLESS_LOG_SUBMISSION_LEVEL, value = "FATAL")
class LogbackFatalLevelForkedTest extends AbstractLogbackLogsIntakeForkedTest {}

@WithConfig(key = LOGS_INJECTION_ENABLED, value = "false")
class LogbackNoInjectionForkedTest extends AbstractLogbackLogsIntakeForkedTest {}

@WithConfig(key = "integration.logback.enabled", value = "false")
class LogbackDisabledForkedTest extends AbstractLogbackLogsIntakeForkedTest {}

@WithConfig(key = TRACE_ENABLED, value = "false")
class LogbackCiAgentlessForkedTest extends AbstractLogbackLogsIntakeForkedTest {}

@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "false")
@WithConfig(key = TRACE_ENABLED, value = "false")
class LogbackCiCorrelationForkedTest extends AbstractLogbackLogsIntakeForkedTest {}
