package datadog.trace.core;

import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_128_BIT_TRACEID_LOGGING_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_128_BIT_TRACEID_GENERATION_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.test.DDCoreSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TraceCorrelationTest extends DDCoreSpecification {

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void getTraceIdWithoutTrace(boolean log128bTraceId) throws Exception {
    String log128bTraceIdConfigValue = Boolean.toString(log128bTraceId);
    injectSysConfig(TRACE_128_BIT_TRACEID_GENERATION_ENABLED, log128bTraceIdConfigValue);
    injectSysConfig(TRACE_128_BIT_TRACEID_LOGGING_ENABLED, log128bTraceIdConfigValue);

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      datadog.trace.bootstrap.instrumentation.api.AgentSpan span = tracer.buildSpan("test").start();
      AgentScope scope = tracer.activateSpan(span);
      scope.close();

      assertEquals("0", tracer.getTraceId());

      scope.close();
      span.finish();
    } finally {
      tracer.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void getTraceIdWithTrace(boolean log128bTraceId) throws Exception {
    String log128bTraceIdConfigValue = Boolean.toString(log128bTraceId);
    injectSysConfig(TRACE_128_BIT_TRACEID_GENERATION_ENABLED, log128bTraceIdConfigValue);
    injectSysConfig(TRACE_128_BIT_TRACEID_LOGGING_ENABLED, log128bTraceIdConfigValue);

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      datadog.trace.bootstrap.instrumentation.api.AgentSpan span = tracer.buildSpan("test").start();
      AgentScope scope = tracer.activateSpan(span);

      datadog.trace.api.DDTraceId traceId = ((DDSpan) scope.span()).getTraceId();
      String formattedTraceId = log128bTraceId ? traceId.toHexString() : traceId.toString();
      assertEquals(formattedTraceId, tracer.getTraceId());

      scope.close();
      span.finish();
    } finally {
      tracer.close();
    }
  }

  @Test
  void getSpanIdWithoutSpan() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      datadog.trace.bootstrap.instrumentation.api.AgentSpan span = tracer.buildSpan("test").start();
      AgentScope scope = tracer.activateSpan(span);
      scope.close();

      assertEquals("0", tracer.getSpanId());

      scope.close();
      span.finish();
    } finally {
      tracer.close();
    }
  }

  @Test
  void getSpanIdWithTrace() throws Exception {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      datadog.trace.bootstrap.instrumentation.api.AgentSpan span = tracer.buildSpan("test").start();
      AgentScope scope = tracer.activateSpan(span);

      assertEquals(
          datadog.trace.api.DDSpanId.toString(((DDSpan) scope.span()).getSpanId()),
          tracer.getSpanId());

      scope.close();
      span.finish();
    } finally {
      tracer.close();
    }
  }
}
