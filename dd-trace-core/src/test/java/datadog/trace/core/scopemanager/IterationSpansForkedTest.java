package datadog.trace.core.scopemanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IterationSpansForkedTest extends DDCoreSpecification {

  ListWriter writer;
  CoreTracer tracer;
  ContinuableScopeManager scopeManager;

  @Mock StatsDClient statsDClient;

  @BeforeEach
  void setup() {
    injectSysConfig("dd.trace.scope.iteration.keep.alive", "1");

    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).statsDClient(statsDClient).build();
    scopeManager = (ContinuableScopeManager) tracer.scopeManager;
  }

  @AfterEach
  void cleanup() {
    tracer.close();
  }

  @Test
  void rootIterationScopeLifecycle() throws Exception {
    tracer.closePrevious(true);
    AgentSpan span1 = tracer.buildSpan("next1").start();
    AgentScope scope1 = tracer.activateNext(span1);

    assertTrue(writer.isEmpty());
    assertEquals(span1, scope1.span());
    assertEquals(scope1, scopeManager.active());
    assertFalse(spanFinished(span1));

    tracer.closePrevious(true);
    AgentSpan span2 = tracer.buildSpan("next2").start();
    AgentScope scope2 = tracer.activateNext(span2);

    assertTrue(spanFinished(span1));
    assertEquals(Collections.singletonList(Collections.singletonList(span1)), writer);

    assertEquals(span2, scope2.span());
    assertEquals(scope2, scopeManager.active());
    assertFalse(spanFinished(span2));

    tracer.closePrevious(true);
    AgentSpan span3 = tracer.buildSpan("next3").start();
    AgentScope scope3 = tracer.activateNext(span3);

    assertTrue(spanFinished(span2));
    assertEquals(
        Arrays.asList(Collections.singletonList(span1), Collections.singletonList(span2)), writer);

    assertEquals(span3, scope3.span());
    assertEquals(scope3, scopeManager.active());
    assertFalse(spanFinished(span3));

    // 'next3' should time out & finish after 1s
    writer.waitForTraces(3);

    assertTrue(spanFinished(span3));
    assertEquals(
        Arrays.asList(
            Collections.singletonList(span1),
            Collections.singletonList(span2),
            Collections.singletonList(span3)),
        writer);

    assertNull(scopeManager.active());
  }

  @Test
  void nonRootIterationScopeLifecycle() throws Exception {
    AgentSpan span0 = tracer.buildSpan("parent").start();
    AgentScope scope0 = tracer.activateSpan(span0);

    tracer.closePrevious(true);
    AgentSpan span1 = tracer.buildSpan("next1").start();
    AgentScope scope1 = tracer.activateNext(span1);

    assertTrue(writer.isEmpty());
    assertEquals(span1, scope1.span());
    assertEquals(scope1, scopeManager.active());
    assertFalse(spanFinished(span1));

    tracer.closePrevious(true);
    AgentSpan span2 = tracer.buildSpan("next2").start();
    AgentScope scope2 = tracer.activateNext(span2);

    assertTrue(spanFinished(span1));
    assertTrue(writer.isEmpty());

    assertEquals(span2, scope2.span());
    assertEquals(scope2, scopeManager.active());
    assertFalse(spanFinished(span2));

    tracer.closePrevious(true);
    AgentSpan span3 = tracer.buildSpan("next3").start();
    AgentScope scope3 = tracer.activateNext(span3);

    assertTrue(spanFinished(span2));
    assertTrue(writer.isEmpty());

    assertEquals(span3, scope3.span());
    assertEquals(scope3, scopeManager.active());
    assertFalse(spanFinished(span3));

    scope0.close();
    span0.finish();
    // closing the parent scope will close & finish 'next3'
    writer.waitForTraces(1);

    assertTrue(spanFinished(span3));
    assertTrue(spanFinished(span0));
    List<DDSpan> firstTrace = new ArrayList<>(writer.firstTrace());
    firstTrace.sort((a, b) -> Long.compare(a.getStartTime(), b.getStartTime()));
    assertEquals(
        Collections.singletonList(Arrays.asList(span0, span1, span2, span3)),
        Collections.singletonList(firstTrace));

    assertNull(scopeManager.active());
  }

  @Test
  void nestedIterationScopeLifecycle() throws Exception {
    tracer.closePrevious(true);
    AgentSpan span1 = tracer.buildSpan("next").start();
    AgentScope scope1 = tracer.activateNext(span1);

    assertTrue(writer.isEmpty());
    assertEquals(span1, scope1.span());
    assertEquals(scope1, scopeManager.active());
    assertFalse(spanFinished(span1));

    AgentSpan span1A = tracer.buildSpan("method").start();
    AgentScope scope1A = tracer.activateSpan(span1A);

    tracer.closePrevious(true);
    AgentSpan span1A1 = tracer.buildSpan("next").start();
    AgentScope scope1A1 = tracer.activateNext(span1A1);

    assertFalse(spanFinished(span1));
    assertTrue(writer.isEmpty());

    assertEquals(span1A1, scope1A1.span());
    assertEquals(scope1A1, scopeManager.active());
    assertFalse(spanFinished(span1A1));

    tracer.closePrevious(true);
    AgentSpan span1A2 = tracer.buildSpan("next").start();
    AgentScope scope1A2 = tracer.activateNext(span1A2);

    assertTrue(spanFinished(span1A1));
    assertTrue(writer.isEmpty());

    assertEquals(span1A2, scope1A2.span());
    assertEquals(scope1A2, scopeManager.active());
    assertFalse(spanFinished(span1A2));

    scope1A.close();
    span1A.finish();
    // closing the intervening scope will close & finish 'next1_2'

    assertTrue(spanFinished(span1A2));
    assertTrue(spanFinished(span1A));
    assertFalse(spanFinished(span1));
    assertTrue(writer.isEmpty());

    // 'next1' should time out & finish after 1s to complete the trace
    writer.waitForTraces(1);

    assertTrue(spanFinished(span1));
    List<DDSpan> firstTrace = new ArrayList<>(writer.firstTrace());
    firstTrace.sort((a, b) -> Long.compare(a.getStartTime(), b.getStartTime()));
    assertEquals(
        Collections.singletonList(Arrays.asList(span1, span1A, span1A1, span1A2)),
        Collections.singletonList(firstTrace));

    assertNull(scopeManager.active());
  }

  private boolean spanFinished(AgentSpan span) {
    return ((DDSpan) span).isFinished();
  }
}
