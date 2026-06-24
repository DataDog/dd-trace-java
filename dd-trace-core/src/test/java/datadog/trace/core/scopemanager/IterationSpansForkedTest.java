package datadog.trace.core.scopemanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = "trace.scope.iteration.keep.alive", value = "1")
class IterationSpansForkedTest extends DDCoreJavaSpecification {

  ListWriter writer;
  CoreTracer tracer;
  StatsDClient statsDClient;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    statsDClient = mock(StatsDClient.class);
    tracer = tracerBuilder().writer(writer).statsDClient(statsDClient).build();
  }

  @AfterEach
  void cleanup() throws Exception {
    tracer.close();
  }

  @Test
  void rootIterationScopeLifecycle() throws Exception {
    tracer.closePrevious(true);
    AgentSpan span1 = tracer.buildSpan("datadog", "next1").start();
    AgentScope scope1 = tracer.activateNext(span1);

    assertTrue(writer.isEmpty());
    assertSame(span1, scope1.span());
    assertSame(span1, tracer.activeSpan());
    assertFalse(spanFinished(span1));

    tracer.closePrevious(true);
    AgentSpan span2 = tracer.buildSpan("datadog", "next2").start();
    AgentScope scope2 = tracer.activateNext(span2);

    assertTrue(spanFinished(span1));
    assertEquals(1, writer.size());
    assertSame(span1, writer.get(0).get(0));
    assertSame(span2, scope2.span());
    assertSame(span2, tracer.activeSpan());
    assertFalse(spanFinished(span2));

    tracer.closePrevious(true);
    AgentSpan span3 = tracer.buildSpan("datadog", "next3").start();
    AgentScope scope3 = tracer.activateNext(span3);
    writer.waitForTraces(2);

    assertTrue(spanFinished(span2));
    assertEquals(2, writer.size());
    assertSame(span3, scope3.span());
    assertSame(span3, tracer.activeSpan());
    assertFalse(spanFinished(span3));

    // 'next3' should time out & finish after 1s
    writer.waitForTraces(3);

    assertTrue(spanFinished(span3));
    assertEquals(3, writer.size());
    assertNull(tracer.activeSpan());
  }

  @Test
  void nonRootIterationScopeLifecycle() throws Exception {
    AgentSpan span0 = tracer.buildSpan("datadog", "parent").start();
    AgentScope scope0 = tracer.activateSpan(span0);

    tracer.closePrevious(true);
    AgentSpan span1 = tracer.buildSpan("datadog", "next1").start();
    AgentScope scope1 = tracer.activateNext(span1);

    assertTrue(writer.isEmpty());
    assertSame(span1, scope1.span());
    assertSame(span1, tracer.activeSpan());
    assertFalse(spanFinished(span1));

    tracer.closePrevious(true);
    AgentSpan span2 = tracer.buildSpan("datadog", "next2").start();
    AgentScope scope2 = tracer.activateNext(span2);

    assertTrue(spanFinished(span1));
    assertTrue(writer.isEmpty());
    assertSame(span2, scope2.span());
    assertSame(span2, tracer.activeSpan());
    assertFalse(spanFinished(span2));

    tracer.closePrevious(true);
    AgentSpan span3 = tracer.buildSpan("datadog", "next3").start();
    AgentScope scope3 = tracer.activateNext(span3);

    assertTrue(spanFinished(span2));
    assertTrue(writer.isEmpty());
    assertSame(span3, scope3.span());
    assertSame(span3, tracer.activeSpan());
    assertFalse(spanFinished(span3));

    scope0.close();
    span0.finish();
    // closing the parent scope will close & finish 'next3'
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
    assertNull(tracer.activeSpan());
  }

  @Test
  void nestedIterationScopeLifecycle() throws Exception {
    tracer.closePrevious(true);
    AgentSpan span1 = tracer.buildSpan("datadog", "next").start();
    AgentScope scope1 = tracer.activateNext(span1);

    assertTrue(writer.isEmpty());
    assertSame(span1, scope1.span());
    assertSame(span1, tracer.activeSpan());
    assertFalse(spanFinished(span1));

    AgentSpan span1A = tracer.buildSpan("datadog", "method").start();
    AgentScope scope1A = tracer.activateSpan(span1A);

    tracer.closePrevious(true);
    AgentSpan span1A1 = tracer.buildSpan("datadog", "next").start();
    AgentScope scope1A1 = tracer.activateNext(span1A1);

    assertFalse(spanFinished(span1));
    assertTrue(writer.isEmpty());
    assertSame(span1A1, scope1A1.span());
    assertSame(span1A1, tracer.activeSpan());
    assertFalse(spanFinished(span1A1));

    tracer.closePrevious(true);
    AgentSpan span1A2 = tracer.buildSpan("datadog", "next").start();
    AgentScope scope1A2 = tracer.activateNext(span1A2);

    assertTrue(spanFinished(span1A1));
    assertTrue(writer.isEmpty());
    assertSame(span1A2, scope1A2.span());
    assertSame(span1A2, tracer.activeSpan());
    assertFalse(spanFinished(span1A2));

    // closing the intervening scope will close & finish 'next1A2'
    scope1A.close();
    span1A.finish();

    assertTrue(spanFinished(span1A2));
    assertTrue(spanFinished(span1A));
    assertFalse(spanFinished(span1));
    assertTrue(writer.isEmpty());

    // 'next1' should time out & finish after 1s to complete the trace
    writer.waitForTraces(1);

    assertTrue(spanFinished(span1));
    sortSpansByStart();
    List<DDSpan> trace = writer.get(0);
    assertEquals(4, trace.size());
    assertSame(span1, trace.get(0));
    assertSame(span1A, trace.get(1));
    assertSame(span1A1, trace.get(2));
    assertSame(span1A2, trace.get(3));
    assertNull(tracer.activeSpan());
  }

  private boolean spanFinished(AgentSpan span) {
    return span instanceof DDSpan && ((DDSpan) span).isFinished();
  }

  private void sortSpansByStart() {
    writer.firstTrace().sort(Comparator.comparingLong(DDSpan::getStartTimeNano));
  }
}
