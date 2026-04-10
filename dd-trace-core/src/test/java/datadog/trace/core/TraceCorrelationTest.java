package datadog.trace.core;

import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_128_BIT_TRACEID_LOGGING_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_128_BIT_TRACEID_GENERATION_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

public class TraceCorrelationTest extends DDCoreJavaSpecification {

  @TableTest({
    "scenario         | log128bTraceId",
    "128-bit enabled  | true          ",
    "128-bit disabled | false         "
  })
  void getTraceIdWithoutTrace(boolean log128bTraceId) {
    WithConfigExtension.injectSysConfig(
        TRACE_128_BIT_TRACEID_GENERATION_ENABLED, String.valueOf(log128bTraceId));
    WithConfigExtension.injectSysConfig(
        TRACE_128_BIT_TRACEID_LOGGING_ENABLED, String.valueOf(log128bTraceId));

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("test").start();
    AgentScope scope = tracer.activateSpan(span);
    scope.close();

    assertEquals("0", tracer.getTraceId());

    span.finish();
    tracer.close();
  }

  @TableTest({
    "scenario         | log128bTraceId",
    "128-bit enabled  | true          ",
    "128-bit disabled | false         "
  })
  void getTraceIdWithTrace(boolean log128bTraceId) {
    WithConfigExtension.injectSysConfig(
        TRACE_128_BIT_TRACEID_GENERATION_ENABLED, String.valueOf(log128bTraceId));
    WithConfigExtension.injectSysConfig(
        TRACE_128_BIT_TRACEID_LOGGING_ENABLED, String.valueOf(log128bTraceId));

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("test").start();
    AgentScope scope = tracer.activateSpan(span);

    DDTraceId traceId = ((DDSpan) scope.span()).getTraceId();
    String formattedTraceId = log128bTraceId ? traceId.toHexString() : traceId.toString();
    assertEquals(formattedTraceId, tracer.getTraceId());

    scope.close();
    span.finish();
    tracer.close();
  }

  @Test
  void getSpanIdWithoutSpan() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("test").start();
    AgentScope scope = tracer.activateSpan(span);
    scope.close();

    assertEquals("0", tracer.getSpanId());

    span.finish();
    tracer.close();
  }

  @Test
  void getSpanIdWithTrace() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    AgentSpan span = tracer.buildSpan("test").start();
    AgentScope scope = tracer.activateSpan(span);

    assertEquals(Long.toString(((DDSpan) scope.span()).getSpanId()), tracer.getSpanId());

    scope.close();
    span.finish();
    tracer.close();
  }
}
