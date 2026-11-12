package datadog.trace.instrumentation.log4j2;

import static datadog.trace.api.CorrelationIdentifier.getSpanId;
import static datadog.trace.api.CorrelationIdentifier.getTraceId;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.async.RingBufferLogEvent;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@WithConfig(key = "logs.pattern.inject", value = "true")
@WithConfig(key = "service", value = "pattern-test")
@WithConfig(key = "env", value = "test-env")
@WithConfig(key = "version", value = "1.2.3")
class Log4j2LegacyAsyncEventTest extends AbstractInstrumentationTest {
  @BeforeEach
  @AfterEach
  void clearContext() {
    ThreadContext.clearAll();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "application-service")
  void enrichesAsyncContextAfterScopeCloses(String applicationService) throws Exception {
    if (applicationService != null) {
      ThreadContext.put("dd.service", applicationService);
    }
    RingBufferLogEvent event = new RingBufferLogEvent();
    AgentSpan span = startSpan("test", "request");
    String traceId;
    String spanId;
    Map<String, String> snapshot;
    try (ContextScope ignored = activateSpan(span)) {
      traceId = getTraceId();
      spanId = getSpanId();
      snapshot = new HashMap<>(ThreadContext.getImmutableContext());
      populate(event, snapshot);
    } finally {
      span.finish();
    }
    // AsyncLogger calls this on its consumer before delivering the event to appenders.
    event.mergePropertiesIntoContextMap(null, null);
    String service = applicationService == null ? "pattern-test" : applicationService;
    assertEquals(
        "message dd.service="
            + service
            + " dd.trace_id="
            + traceId
            + " dd.span_id="
            + spanId
            + " dd.env=test-env dd.version=1.2.3"
            + System.lineSeparator(),
        layout().toSerializable(event));
    assertEquals(applicationService, snapshot.get("dd.service"));

    // Ring-buffer slots are reused. A background event must not retain the previous IDs.
    event.clear();
    populate(event, emptyMap());
    event.mergePropertiesIntoContextMap(null, null);
    assertEquals(
        "message dd.service=pattern-test dd.trace_id=0 dd.span_id=0 dd.env=test-env dd.version=1.2.3"
            + System.lineSeparator(),
        layout().toSerializable(event));
    assertFalse(event.getContextMap().containsKey("dd.trace_id"));
  }

  @Test
  void preservesLoggerConfiguredService() throws Exception {
    RingBufferLogEvent event = new RingBufferLogEvent();
    populate(event, emptyMap());
    event.mergePropertiesIntoContextMap(
        singletonMap(Property.createProperty("dd.service", "logger-service"), false), null);
    assertEquals("logger-service", event.getContextMap().get("dd.service"));
  }

  @Test
  void preservesDeploymentFieldsWithoutCopyingCompleteContext() throws Exception {
    Map<String, String> context = new HashMap<>();
    context.put("dd.service", "application-service");
    context.put("dd.env", "application-env");
    context.put("dd.version", "application-version");
    RingBufferLogEvent event = new RingBufferLogEvent();
    populate(event, context);
    event.mergePropertiesIntoContextMap(null, null);
    assertSame(context, event.getContextMap());
    assertEquals(
        "message dd.service=application-service dd.trace_id=0 dd.span_id=0 dd.env=application-env dd.version=application-version"
            + System.lineSeparator(),
        layout().toSerializable(event));
  }

  @Test
  void preservesLoggerConfiguredDeploymentFields() throws Exception {
    RingBufferLogEvent event = new RingBufferLogEvent();
    populate(event, emptyMap());
    Map<Property, Boolean> properties = new HashMap<>();
    properties.put(Property.createProperty("dd.env", "logger-env"), false);
    properties.put(Property.createProperty("dd.version", "logger-version"), false);
    event.mergePropertiesIntoContextMap(properties, null);
    assertEquals("logger-env", event.getContextMap().get("dd.env"));
    assertEquals("logger-version", event.getContextMap().get("dd.version"));
    assertEquals("pattern-test", event.getContextMap().get("dd.service"));
  }

  private static PatternLayout layout() {
    return PatternLayout.newBuilder().withPattern("%m%n").build();
  }

  private static void populate(RingBufferLogEvent event, Map<String, String> context)
      throws Exception {
    // Log4j2 changed setValues' thread/timing arguments between 2.0 and 2.6.
    for (Method method : RingBufferLogEvent.class.getMethods()) {
      if (!method.getName().equals("setValues")) {
        continue;
      }
      Class<?>[] types = method.getParameterTypes();
      Object[] arguments = new Object[types.length];
      for (int i = 0; i < types.length; i++) {
        Class<?> type = types[i];
        if (type == Map.class) arguments[i] = context;
        else if (type == Message.class) arguments[i] = new SimpleMessage("message");
        else if (type == Level.class) arguments[i] = Level.INFO;
        else if (type == String.class) arguments[i] = "test";
        else if (type == long.class) arguments[i] = 0L;
        else if (type == int.class) arguments[i] = 0;
      }
      method.invoke(event, arguments);
      return;
    }
    throw new AssertionError("RingBufferLogEvent.setValues not found");
  }
}
