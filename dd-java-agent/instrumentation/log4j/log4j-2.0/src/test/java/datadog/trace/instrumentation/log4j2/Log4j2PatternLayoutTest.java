package datadog.trace.instrumentation.log4j2;

import static datadog.trace.api.CorrelationIdentifier.getSpanId;
import static datadog.trace.api.CorrelationIdentifier.getTraceId;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@WithConfig(key = "logs.pattern.inject", value = "true")
@WithConfig(key = "service", value = "pattern-test")
class Log4j2PatternLayoutTest extends AbstractInstrumentationTest {
  private static final String FIELDS =
      "dd.service=%X{dd.service} dd.trace_id=%X{dd.trace_id} dd.span_id=%X{dd.span_id}";

  @BeforeEach
  @AfterEach
  protected void clearContext() {
    ThreadContext.clearAll();
  }

  @Test
  protected void injectsBuilderPatternAndPreservesPrefix() {
    String pattern = "[%d{yyyy-MM-dd HH:mm:ss}] %p %m%n";
    PatternLayout layout = PatternLayout.newBuilder().withPattern(pattern).build();
    String expected = "[%d{yyyy-MM-dd HH:mm:ss}] " + FIELDS + " %p %m%n";
    assertEquals(expected, layout.getConversionPattern());
    assertEquals(
        expected, PatternLayout.newBuilder().withPattern(expected).build().getConversionPattern());
  }

  @Test
  @WithConfig(key = "logs.pattern.inject", value = "false")
  protected void leavesPatternAloneWhenDisabled() {
    assertEquals(
        "%m%n", PatternLayout.newBuilder().withPattern("%m%n").build().getConversionPattern());
  }

  @Test
  protected void capturesServiceTraceAndSpanForDeferredFormatting() {
    PatternLayout layout = PatternLayout.newBuilder().withPattern("%p %m%n").build();
    AgentSpan span = startSpan("test", "request");
    LogEvent captured;
    String traceId;
    String spanId;
    try (ContextScope ignored = activateSpan(span)) {
      traceId = getTraceId();
      spanId = getSpanId();
      captured = Events.event("message");
    } finally {
      span.finish();
    }
    assertEquals(
        "INFO dd.service=pattern-test dd.trace_id="
            + traceId
            + " dd.span_id="
            + spanId
            + " message"
            + System.lineSeparator(),
        layout.toSerializable(captured));
  }

  @Test
  protected void keepsServiceWithoutInventingIds() {
    PatternLayout layout = PatternLayout.newBuilder().withPattern("%p %m%n").build();
    assertEquals(
        "INFO dd.service=pattern-test dd.trace_id= dd.span_id= message" + System.lineSeparator(),
        layout.toSerializable(Events.event("message")));
  }

  @Test
  protected void preservesApplicationService() {
    ThreadContext.put("dd.service", "application-service");
    PatternLayout layout = PatternLayout.newBuilder().withPattern("%p %m%n").build();
    assertEquals(
        "INFO dd.service=application-service dd.trace_id= dd.span_id= message"
            + System.lineSeparator(),
        layout.toSerializable(Events.event("message")));
  }

  @Test
  protected void injectsDefaultAndNullBuilderPatterns() {
    assertEquals("%m " + FIELDS + "%n", PatternLayout.createDefaultLayout().getConversionPattern());
    assertEquals(
        "%m " + FIELDS + "%n",
        PatternLayout.newBuilder().withPattern(null).build().getConversionPattern());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "%X{}",
        "%X",
        "%X{dd.service,dd.trace_id,dd.span_id}",
        "%mdc{dd.service}",
        "%MDC{dd.trace_id}",
        "%%X{dd.trace_id}",
        "%style{%X{dd.span_id}}{red}"
      })
  protected void printsEveryExistingOrMissingValueExactlyOnce(String converter) {
    ThreadContext.put("dd.service", "service-marker");
    ThreadContext.put("dd.trace_id", "trace-marker");
    ThreadContext.put("dd.span_id", "span-marker");
    PatternLayout layout = PatternLayout.newBuilder().withPattern(converter + " %m%n").build();
    String output = layout.toSerializable(Events.event("message"));
    for (String marker : new String[] {"service-marker", "trace-marker", "span-marker"}) {
      assertEquals(2, output.split(marker, -1).length, output);
    }
  }

  private static class Events {
    private static LogEvent event(String message) {
      return new Log4jLogEvent(
          "test",
          null,
          Log4j2PatternLayoutTest.class.getName(),
          Level.INFO,
          new SimpleMessage(message),
          null);
    }
  }
}

@WithConfig(key = "logs.pattern.inject", value = "true")
@WithConfig(key = "logs.injection.enabled", value = "false")
class Log4j2PatternLayoutLogsInjectionDisabledTest extends AbstractInstrumentationTest {
  @Test
  protected void respectsDisabledMdcInjection() {
    ThreadContext.clearAll();
    PatternLayout layout = PatternLayout.newBuilder().withPattern("%p %m%n").build();
    AgentSpan span = startSpan("test", "request");
    try (ContextScope ignored = activateSpan(span)) {
      Log4jLogEvent event =
          new Log4jLogEvent(
              "test", null, getClass().getName(), Level.INFO, new SimpleMessage("message"), null);
      assertEquals(
          "INFO dd.service= dd.trace_id= dd.span_id= message" + System.lineSeparator(),
          layout.toSerializable(event));
    } finally {
      span.finish();
      ThreadContext.clearAll();
    }
  }
}
