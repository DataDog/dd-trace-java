package datadog.trace.agent.integration.opentracing;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.context.TraceScope;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import org.junit.jupiter.api.Test;

/** Simple set of tests to verify the OpenTracing instrumentation is added correctly. */
class OpenTracingTest {

  private final Tracer tracer = GlobalTracer.get();

  @Test
  void testTracerRegistered() {
    // expect
    assertTrue(GlobalTracer.isRegistered());
  }

  @Test
  void testSpanScopeInterfaces() {
    // setup
    Span span = tracer.buildSpan("test").start();

    // expect
    assertInstanceOf(MutableSpan.class, span);

    // when
    try (Scope scope = tracer.scopeManager().activate(span, false)) {
      // then
      assertInstanceOf(TraceScope.class, scope);
    }
  }
}
