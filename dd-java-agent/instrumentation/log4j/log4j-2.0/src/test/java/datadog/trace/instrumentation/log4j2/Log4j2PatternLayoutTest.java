package datadog.trace.instrumentation.log4j2;

import static datadog.trace.api.CorrelationIdentifier.getSpanId;
import static datadog.trace.api.CorrelationIdentifier.getTraceId;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
@WithConfig(key = "env", value = "test-env")
@WithConfig(key = "version", value = "1.2.3")
class Log4j2PatternLayoutTest extends AbstractInstrumentationTest {
  private static final String FIELDS =
      Log4j2PatternLayoutTest.class
                  .getClassLoader()
                  .getResource(
                      "org/apache/logging/log4j/core/pattern/EqualsReplacementConverter.class")
              != null
          ? "dd.service=%X{dd.service} dd.trace_id=%equals{%X{dd.trace_id}}{}{0} "
              + "dd.span_id=%equals{%X{dd.span_id}}{}{0} dd.env=%X{dd.env} dd.version=%X{dd.version}"
          : "dd.service=%X{dd.service} dd.trace_id=%replace{%X{dd.trace_id}}{^$}{0} "
              + "dd.span_id=%replace{%X{dd.span_id}}{^$}{0} dd.env=%X{dd.env} dd.version=%X{dd.version}";

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
            + " dd.env=test-env dd.version=1.2.3 message"
            + System.lineSeparator(),
        layout.toSerializable(captured));
  }

  @Test
  protected void keepsDeploymentFieldsWithZeroIds() {
    PatternLayout layout = PatternLayout.newBuilder().withPattern("%p %m%n").build();
    assertEquals(
        "INFO dd.service=pattern-test dd.trace_id=0 dd.span_id=0 dd.env=test-env dd.version=1.2.3 message"
            + System.lineSeparator(),
        layout.toSerializable(Events.event("message")));
  }

  @Test
  protected void preservesApplicationService() {
    ThreadContext.put("dd.service", "application-service");
    PatternLayout layout = PatternLayout.newBuilder().withPattern("%p %m%n").build();
    assertEquals(
        "INFO dd.service=application-service dd.trace_id=0 dd.span_id=0 dd.env=test-env dd.version=1.2.3 message"
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
        "%X{dd.service,dd.trace_id,dd.span_id,dd.env,dd.version}",
        "%cyan{%X{dd.env}}",
        "%style{%X{dd.version}}{red}",
        "%X{dd.env}",
        "%MDC{dd.version}",
        "%mdc{dd.service}",
        "%MDC{dd.trace_id}",
        "%%X{dd.trace_id}",
        "%style{%X{dd.span_id}}{red}",
        "%black{%X{dd.trace_id}}",
        "%blue{%X}",
        "%cyan{%X{dd.trace_id}}",
        "%green{%X{dd.trace_id}}",
        "%magenta{%X{dd.trace_id}}",
        "%red{%X{dd.trace_id}}",
        "%white{%X{dd.trace_id}}",
        "%yellow{%X{dd.trace_id}}",
        "%maxLength{%X{dd.trace_id}}{100}",
        "%maxLen{%X{dd.trace_id}}{100}",
        "%upperCase{%X{dd.trace_id}}",
        "%lowerCase{%X{dd.trace_id}}"
      })
  protected void printsEveryExistingOrMissingValueExactlyOnce(String converter) {
    if (converter.startsWith("%max")) {
      assumeTrue(
          getClass()
                  .getClassLoader()
                  .getResource("org/apache/logging/log4j/core/pattern/MaxLengthConverter.class")
              != null);
    }
    ThreadContext.put("dd.env", "env-marker");
    ThreadContext.put("dd.version", "version-marker");
    ThreadContext.put("dd.service", "service-marker");
    ThreadContext.put("dd.trace_id", "trace-marker");
    ThreadContext.put("dd.span_id", "span-marker");
    PatternLayout layout = PatternLayout.newBuilder().withPattern(converter + " %m%n").build();
    String output = layout.toSerializable(Events.event("message"));
    for (String marker :
        new String[] {
          "service-marker", "trace-marker", "span-marker", "env-marker", "version-marker"
        }) {
      assertEquals(2, output.split(marker, -1).length, output);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"%replace{%m}{'}{}%n", "%replace{%m}{\"}{}%n"})
  void keepsCorrelationFieldsOnTheMessageLineWithLiteralQuotes(String pattern) {
    ThreadContext.put("dd.service", "application-service");
    PatternLayout layout = PatternLayout.newBuilder().withPattern(pattern).build();
    assertEquals(
        "message dd.service=application-service dd.trace_id=0 dd.span_id=0 dd.env=test-env dd.version=1.2.3"
            + System.lineSeparator(),
        layout.toSerializable(Events.event("message")));
  }

  @Test
  @WithConfig(key = "env", value = "")
  @WithConfig(key = "version", value = "")
  void leavesUndefinedDeploymentFieldsEmpty() {
    PatternLayout layout = PatternLayout.newBuilder().withPattern("%p %m%n").build();
    assertEquals(
        "INFO dd.service=pattern-test dd.trace_id=0 dd.span_id=0 dd.env= dd.version= message"
            + System.lineSeparator(),
        layout.toSerializable(Events.event("message")));
  }

  @Test
  void preservesApplicationDeploymentFields() {
    ThreadContext.put("dd.env", "application-env");
    ThreadContext.put("dd.version", "application-version");
    PatternLayout layout = PatternLayout.newBuilder().withPattern("%p %m%n").build();
    assertEquals(
        "INFO dd.service=pattern-test dd.trace_id=0 dd.span_id=0 dd.env=application-env dd.version=application-version message"
            + System.lineSeparator(),
        layout.toSerializable(Events.event("message")));
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
          "INFO dd.service= dd.trace_id=0 dd.span_id=0 dd.env= dd.version= message"
              + System.lineSeparator(),
          layout.toSerializable(event));
    } finally {
      span.finish();
      ThreadContext.clearAll();
    }
  }
}
