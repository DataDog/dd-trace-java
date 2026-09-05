package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.propagation.Propagators;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.common.writer.NoOpWriter;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for the propagate-only mode enabled by DD_PROPAGATE_CONTEXT: context propagation stays
 * active while no trace data is reported to Datadog.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ContextPropagationOnlyTest extends DDCoreJavaSpecification {

  @Test
  @WithConfig(key = TraceInstrumentationConfig.TRACE_ENABLED, value = "false")
  @WithConfig(key = TraceInstrumentationConfig.PROPAGATE_CONTEXT, value = "true")
  void verifyPropagateOnlyModeDoesNotReportTraces() {
    CoreTracer tracer = tracerBuilder().build();

    assertInstanceOf(NoOpWriter.class, tracer.writer);
    assertFalse(tracer.captureTraceConfig().isTraceEnabled());
  }

  @Test
  @WithConfig(key = TraceInstrumentationConfig.TRACE_ENABLED, value = "false")
  @WithConfig(key = TraceInstrumentationConfig.PROPAGATE_CONTEXT, value = "true")
  void verifyPropagateOnlyModeStillInjectsContext() {
    CoreTracer tracer = tracerBuilder().build();
    AgentSpan span = tracer.buildSpan("test", "operation").start();
    try {
      Map<String, String> carrier = new HashMap<>();
      Propagators.defaultPropagator().inject(Context.root().with(span), carrier, Map::put);

      assertNotNull(carrier.get("traceparent"), "traceparent missing: " + carrier);
      assertNotNull(carrier.get("x-datadog-trace-id"), "x-datadog-trace-id missing: " + carrier);
      assertTrue(
          carrier.get("traceparent").contains(span.context().getTraceId().toHexString()),
          "traceparent does not match trace id: " + carrier);
    } finally {
      span.finish();
    }
  }

  @Test
  @WithConfig(key = TraceInstrumentationConfig.PROPAGATE_CONTEXT, value = "true")
  void verifyFlagHasNoEffectWhenTracingEnabled() {
    CoreTracer tracer = tracerBuilder().build();

    assertInstanceOf(DDAgentWriter.class, tracer.writer);
    assertTrue(tracer.captureTraceConfig().isTraceEnabled());
  }

  @Test
  @WithConfig(key = TraceInstrumentationConfig.TRACE_ENABLED, value = "false")
  void verifyTracingDisabledWithoutFlagKeepsCurrentBehavior() {
    CoreTracer tracer = tracerBuilder().build();

    assertInstanceOf(DDAgentWriter.class, tracer.writer);
    assertTrue(tracer.captureTraceConfig().isTraceEnabled());
  }
}
