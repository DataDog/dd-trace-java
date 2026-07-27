package datadog.opentracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import io.opentracing.ScopeManager;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = "trace.scope.iteration.keep.alive", value = "1")
class IterationSpansForkedTest extends DDJavaSpecification {

  ListWriter writer;
  DDTracer tracer;
  ScopeManager scopeManager;
  StatsDClient statsDClient;
  CoreTracer coreTracer;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    statsDClient = mock(StatsDClient.class);
    tracer = DDTracer.builder().writer(writer).statsDClient(statsDClient).build();
    scopeManager = tracer.scopeManager();
    coreTracer = (CoreTracer) tracer.getInternalTracer();
  }

  @AfterEach
  void cleanup() throws Exception {
    coreTracer.close();
  }

  @Test
  void rootIterationScopeLifecycle() throws Exception {
    coreTracer.closePrevious(true);
    AgentSpan span1 = coreTracer.buildSpan("datadog", "next1").start();
    AgentScope scope1 = coreTracer.activateNext(span1);

    assertTrue(writer.isEmpty());
    assertSame(span1, scope1.span());
    assertSame(span1, ((OTSpan) tracer.activeSpan()).getDelegate());
    assertFalse(spanFinished(span1));

    coreTracer.closePrevious(true);
    AgentSpan span2 = coreTracer.buildSpan("datadog", "next2").start();
    AgentScope scope2 = coreTracer.activateNext(span2);

    assertTrue(spanFinished(span1));
    assertEquals(1, writer.size());
    assertSame(span1, writer.get(0).get(0));
    assertSame(span2, scope2.span());
    assertSame(span2, ((OTSpan) tracer.activeSpan()).getDelegate());
    assertFalse(spanFinished(span2));

    coreTracer.closePrevious(true);
    AgentSpan span3 = coreTracer.buildSpan("datadog", "next3").start();
    AgentScope scope3 = coreTracer.activateNext(span3);
    writer.waitForTraces(2);

    assertTrue(spanFinished(span2));
    assertEquals(2, writer.size());
    assertSame(span3, scope3.span());
    assertSame(span3, ((OTSpan) tracer.activeSpan()).getDelegate());
    assertFalse(spanFinished(span3));

    // 'next3' should time out & finish after 1s
    writer.waitForTraces(3);

    assertTrue(spanFinished(span3));
    assertEquals(3, writer.size());
  }

  @Test
  void nonRootIterationScopeLifecycle() throws Exception {
    AgentSpan span0 = coreTracer.buildSpan("datadog", "parent").start();
    AgentScope scope0 = coreTracer.activateSpan(span0);

    coreTracer.closePrevious(true);
    AgentSpan span1 = coreTracer.buildSpan("datadog", "next1").start();
    AgentScope scope1 = coreTracer.activateNext(span1);

    assertTrue(writer.isEmpty());
    assertSame(span1, scope1.span());
    assertSame(span1, ((OTSpan) tracer.activeSpan()).getDelegate());
    assertFalse(spanFinished(span1));

    coreTracer.closePrevious(true);
    AgentSpan span2 = coreTracer.buildSpan("datadog", "next2").start();
    AgentScope scope2 = coreTracer.activateNext(span2);

    assertTrue(spanFinished(span1));
    assertTrue(writer.isEmpty());
    assertSame(span2, scope2.span());
    assertSame(span2, ((OTSpan) tracer.activeSpan()).getDelegate());
    assertFalse(spanFinished(span2));

    coreTracer.closePrevious(true);
    AgentSpan span3 = coreTracer.buildSpan("datadog", "next3").start();
    AgentScope scope3 = coreTracer.activateNext(span3);

    assertTrue(spanFinished(span2));
    assertTrue(writer.isEmpty());
    assertSame(span3, scope3.span());
    assertSame(span3, ((OTSpan) tracer.activeSpan()).getDelegate());
    assertFalse(spanFinished(span3));

    // close and finish the surrounding (non-iteration) span to complete the trace
    scope0.close();
    span0.finish();
    writer.waitForTraces(1);

    assertTrue(spanFinished(span3));
    assertTrue(spanFinished(span0));
    sortSpansByStart();
    List<DDSpan> trace = writer.get(0);
    assertEquals(4, trace.size());
    assertSame(span0, trace.get(0));
    assertSame(span1, trace.get(1));
    assertSame(span2, trace.get(2));
    assertSame(span3, trace.get(3));
  }

  @Test
  void nestedIterationScopeLifecycle() throws Exception {
    coreTracer.closePrevious(true);
    AgentSpan span1 = coreTracer.buildSpan("datadog", "next1").start();
    AgentScope scope1 = coreTracer.activateNext(span1);

    assertTrue(writer.isEmpty());
    assertSame(span1, scope1.span());
    assertSame(span1, ((OTSpan) tracer.activeSpan()).getDelegate());
    assertFalse(spanFinished(span1));

    AgentSpan span1A = coreTracer.buildSpan("datadog", "methodA").start();
    AgentScope scope1A = coreTracer.activateSpan(span1A);

    coreTracer.closePrevious(true);
    AgentSpan span1A1 = coreTracer.buildSpan("datadog", "next1A1").start();
    AgentScope scope1A1 = coreTracer.activateNext(span1A1);

    assertFalse(spanFinished(span1));
    assertTrue(writer.isEmpty());
    assertSame(span1A1, scope1A1.span());
    assertSame(span1A1, ((OTSpan) tracer.activeSpan()).getDelegate());
    assertFalse(spanFinished(span1A1));

    coreTracer.closePrevious(true);
    AgentSpan span1A2 = coreTracer.buildSpan("datadog", "next1A2").start();
    AgentScope scope1A2 = coreTracer.activateNext(span1A2);

    assertTrue(spanFinished(span1A1));
    assertTrue(writer.isEmpty());
    assertSame(span1A2, scope1A2.span());
    assertSame(span1A2, ((OTSpan) tracer.activeSpan()).getDelegate());
    assertFalse(spanFinished(span1A2));

    // close and finish the intermediate (non-iteration) span
    scope1A.close();
    span1A.finish();
    // 'next1' (and 'next1A2') should time out & finish after 1s to complete the trace
    writer.waitForTraces(1);

    assertTrue(spanFinished(span1A2));
    assertTrue(spanFinished(span1A));
    assertTrue(spanFinished(span1));
    sortSpansByStart();
    List<DDSpan> trace = writer.get(0);
    assertEquals(4, trace.size());
    assertSame(span1, trace.get(0));
    assertSame(span1A, trace.get(1));
    assertSame(span1A1, trace.get(2));
    assertSame(span1A2, trace.get(3));
  }

  private boolean spanFinished(AgentSpan span) {
    return span instanceof DDSpan && ((DDSpan) span).isFinished();
  }

  private void sortSpansByStart() {
    writer.firstTrace().sort(Comparator.comparingLong(DDSpan::getStartTime));
  }
}
