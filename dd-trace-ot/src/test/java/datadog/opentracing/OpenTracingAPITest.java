package datadog.opentracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTags;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.context.TraceScope;
import datadog.trace.core.DDSpan;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapAdapter;
import io.opentracing.tag.Tags;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenTracingAPITest extends DDJavaSpecification {

  ListWriter writer = new ListWriter();
  DDTracer tracer = DDTracer.builder().writer(writer).build();
  TraceInterceptor traceInterceptor = mock(TraceInterceptor.class);
  ScopeListener scopeListener = mock(ScopeListener.class);

  @BeforeEach
  void setup() {
    assertNull(tracer.scopeManager().active());
    tracer.addTraceInterceptor(traceInterceptor);
    tracer.getInternalTracer().addScopeListener(scopeListener);
    // stub onTraceComplete to pass traces through (return first argument)
    when(traceInterceptor.onTraceComplete(any())).thenAnswer(inv -> inv.getArgument(0));
    // clear interactions from setup (e.g. priority() called during addTraceInterceptor)
    clearInvocations(traceInterceptor, scopeListener);
  }

  @AfterEach
  void tearDown() throws Exception {
    tracer.close();
  }

  @Test
  void tracerScopeManagerReturnsNullForNoActiveSpan() {
    assertNull(tracer.activeSpan());
    assertNull(tracer.scopeManager().active());
    assertNull(tracer.scopeManager().activeSpan());
  }

  @Test
  void singleSpan() throws Exception {
    Scope scope = null;
    try {
      scope = tracer.buildSpan("someOperation").startActive(true);
      scope.span().setTag(DDTags.SERVICE_NAME, "someService");
    } finally {
      if (scope != null) scope.close();
    }
    writer.waitForTraces(1);

    verify(traceInterceptor).onTraceComplete(any());

    assertEquals(1, writer.size());
    List<DDSpan> trace = writer.get(0);
    assertEquals(1, trace.size());
    DDSpan span = trace.get(0);
    assertEquals("someService", span.getServiceName());
    assertEquals("someOperation", span.getOperationName().toString());
    assertEquals("someOperation", span.getResourceName().toString());
    assertEquals("opentracing", span.getTags().get(DDTags.DD_INTEGRATION));
  }

  @Test
  void spanWithBuilder() throws Exception {
    Span testSpan =
        tracer
            .buildSpan("someOperation")
            .withTag(Tags.COMPONENT, "opentracing")
            .withTag("someBoolean", true)
            .withTag("someNumber", 1)
            .withTag(DDTags.SERVICE_NAME, "someService")
            .start();

    Scope scope = null;
    try {
      scope = tracer.activateSpan(testSpan);
      testSpan.finish();
    } finally {
      if (scope != null) scope.close();
    }
    writer.waitForTraces(1);

    verify(traceInterceptor).onTraceComplete(any());
    assertTrue(testSpan instanceof MutableSpan);

    assertEquals(1, writer.size());
    List<DDSpan> trace = writer.get(0);
    assertEquals(1, trace.size());
    DDSpan span = trace.get(0);
    assertEquals("someService", span.getServiceName());
    assertEquals("someOperation", span.getOperationName().toString());
    assertEquals("someOperation", span.getResourceName().toString());
    assertEquals("opentracing", span.getTags().get(Tags.COMPONENT.getKey()));
    assertEquals("opentracing", span.getTags().get(DDTags.DD_INTEGRATION));
    assertEquals(true, span.getTags().get("someBoolean"));
    assertEquals(1, span.getTags().get("someNumber"));
  }

  @Test
  void singleSpanWithManualStartFinish() throws Exception {
    Span testSpan = tracer.buildSpan("someOperation").start();
    Scope scope = tracer.activateSpan(testSpan);

    verify(scopeListener).afterScopeActivated();
    assertTrue(testSpan instanceof MutableSpan);
    assertTrue(scope.span() instanceof MutableSpan);

    testSpan.setTag(DDTags.SERVICE_NAME, "someService");
    testSpan.setTag(Tags.COMPONENT, "opentracing");
    testSpan.setTag("someBoolean", true);
    testSpan.setTag("someNumber", 1);
    testSpan.setOperationName("someOtherOperation");
    scope.close();
    testSpan.finish();
    writer.waitForTraces(1);

    verify(traceInterceptor).onTraceComplete(any());
    verify(scopeListener).afterScopeClosed();

    assertEquals(1, writer.size());
    List<DDSpan> trace = writer.get(0);
    assertEquals(1, trace.size());
    DDSpan span = trace.get(0);
    assertEquals("someService", span.getServiceName());
    assertEquals("someOtherOperation", span.getOperationName().toString());
    assertEquals("someOtherOperation", span.getResourceName().toString());
    assertEquals("opentracing", span.getTags().get(Tags.COMPONENT.getKey()));
    assertEquals("opentracing", span.getTags().get(DDTags.DD_INTEGRATION));
    assertEquals(true, span.getTags().get("someBoolean"));
    assertEquals(1, span.getTags().get("someNumber"));
  }

  @Test
  void spansAndScopesAllEqual() throws Exception {
    Span testSpan = tracer.buildSpan("someOperation").start();
    Scope testScope = tracer.activateSpan(testSpan);

    Span traceActiveSpan = tracer.activeSpan();
    Span scopeManagerActiveSpan = tracer.scopeManager().activeSpan();
    Span scopeActiveSpan = testScope.span();

    Scope scopeManagerActiveScope = tracer.scopeManager().active();
    testScope.close();
    testSpan.finish();
    writer.waitForTraces(1);

    verify(traceInterceptor).onTraceComplete(any());
    assertEquals(testSpan, traceActiveSpan);
    assertEquals(testSpan.hashCode(), traceActiveSpan.hashCode());
    assertEquals(testSpan, scopeManagerActiveSpan);
    assertEquals(testSpan.hashCode(), scopeManagerActiveSpan.hashCode());
    assertEquals(testSpan, scopeActiveSpan);
    assertEquals(testSpan.hashCode(), scopeActiveSpan.hashCode());
    assertEquals(testScope, scopeManagerActiveScope);
    assertEquals(testScope.hashCode(), scopeManagerActiveScope.hashCode());
  }

  @Test
  void nestedSpans() throws Exception {
    Scope scope = null;
    try {
      scope = tracer.buildSpan("someOperation").startActive(true);
      scope.span().setTag(DDTags.SERVICE_NAME, "someService");

      Scope scope2 = null;
      try {
        scope2 = tracer.buildSpan("someOperation2").startActive(true);
      } finally {
        if (scope2 != null) scope2.close();
      }
    } finally {
      if (scope != null) scope.close();
    }
    writer.waitForTraces(1);

    verify(traceInterceptor).onTraceComplete(any());

    assertEquals(1, writer.size());
    List<DDSpan> trace = writer.get(0);
    assertEquals(2, trace.size());
    DDSpan parentSpan = trace.get(0);
    DDSpan childSpan = trace.get(1);
    assertEquals("someService", parentSpan.getServiceName());
    assertEquals("someOperation", parentSpan.getOperationName().toString());
    assertEquals("someOperation", parentSpan.getResourceName().toString());
    assertEquals("someService", childSpan.getServiceName());
    assertEquals("someOperation2", childSpan.getOperationName().toString());
    assertEquals("someOperation2", childSpan.getResourceName().toString());
    assertEquals(parentSpan.getSpanId(), childSpan.getParentId());
  }

  @Test
  void spanWithAsyncPropagation() throws Exception {
    AgentTracer.TracerAPI internalTracer = tracer.getInternalTracer();

    Scope scope =
        tracer
            .buildSpan("someOperation")
            .withTag(DDTags.SERVICE_NAME, "someService")
            .startActive(true);
    internalTracer.setAsyncPropagationEnabled(false);

    assertTrue(scope instanceof TraceScope);
    assertTrue(!internalTracer.isAsyncPropagationEnabled());

    internalTracer.setAsyncPropagationEnabled(true);
    TraceScope.Continuation continuation = ((TraceScope) scope).capture();

    assertTrue(internalTracer.isAsyncPropagationEnabled());
    assertNotNull(continuation);

    continuation.cancel();
    scope.close();
    writer.waitForTraces(1);

    verify(traceInterceptor).onTraceComplete(any());

    assertEquals(1, writer.size());
    List<DDSpan> trace = writer.get(0);
    assertEquals(1, trace.size());
    DDSpan span = trace.get(0);
    assertEquals("someService", span.getServiceName());
    assertEquals("someOperation", span.getOperationName().toString());
    assertEquals("someOperation", span.getResourceName().toString());
  }

  @Test
  void spanInheritsAsyncPropagation() throws Exception {
    AgentTracer.TracerAPI internalTracer = tracer.getInternalTracer();

    Scope outer =
        tracer
            .buildSpan("someOperation")
            .withTag(DDTags.SERVICE_NAME, "someService")
            .startActive(true);
    internalTracer.setAsyncPropagationEnabled(false);

    assertTrue(!internalTracer.isAsyncPropagationEnabled());

    internalTracer.setAsyncPropagationEnabled(true);
    Scope inner =
        tracer
            .buildSpan("otherOperation")
            .withTag(DDTags.SERVICE_NAME, "otherService")
            .startActive(true);

    assertTrue(internalTracer.isAsyncPropagationEnabled());

    inner.close();
    outer.close();
    writer.waitForTraces(1);

    verify(traceInterceptor).onTraceComplete(any());

    assertEquals(1, writer.size());
    List<DDSpan> trace = writer.get(0);
    assertEquals(2, trace.size());
    DDSpan outerSpan = trace.get(0);
    DDSpan innerSpan = trace.get(1);
    assertEquals("someService", outerSpan.getServiceName());
    assertEquals("someOperation", outerSpan.getOperationName().toString());
    assertEquals("someOperation", outerSpan.getResourceName().toString());
    assertEquals("otherService", innerSpan.getServiceName());
    assertEquals("otherOperation", innerSpan.getOperationName().toString());
    assertEquals("otherOperation", innerSpan.getResourceName().toString());
  }

  @Test
  void spanContextIdsEqualTracerIds() throws Exception {
    Span testSpan = tracer.buildSpan("someOperation").withServiceName("someService").start();
    Scope scope = tracer.activateSpan(testSpan);

    verify(scopeListener).afterScopeActivated();

    assertEquals(testSpan.context().toSpanId(), tracer.getSpanId());
    assertEquals(
        testSpan.context().toTraceId(),
        tracer.getInternalTracer().activeSpan().spanContext().getTraceId().toString());

    scope.close();
    testSpan.finish();
    writer.waitForTraces(1);

    verify(traceInterceptor).onTraceComplete(any());
    verify(scopeListener).afterScopeClosed();

    assertEquals(1, writer.size());
    List<DDSpan> trace = writer.get(0);
    assertEquals(1, trace.size());
    DDSpan span = trace.get(0);
    assertEquals("someService", span.getServiceName());
    assertEquals("someOperation", span.getOperationName().toString());
    assertEquals("someOperation", span.getResourceName().toString());
  }

  @Test
  void closingScopeWhenNotOnTop() throws Exception {
    Span firstSpan = tracer.buildSpan("someOperation").start();
    Scope firstScope = tracer.activateSpan(firstSpan);
    Span secondSpan = tracer.buildSpan("someOperation").start();
    Scope secondScope = tracer.activateSpan(secondSpan);
    firstSpan.finish();
    firstScope.close();

    // then: 2 * scopeListener.afterScopeActivated(), 0 * _
    verify(scopeListener, times(2)).afterScopeActivated();
    verifyNoMoreInteractions(scopeListener, traceInterceptor);
    clearInvocations(scopeListener, traceInterceptor);

    secondSpan.finish();
    secondScope.close();
    writer.waitForTraces(1);

    // then: 2 * scopeListener.afterScopeClosed(), 1 * traceInterceptor.onTraceComplete(...)
    verify(scopeListener, times(2)).afterScopeClosed();
    verify(traceInterceptor).onTraceComplete(any());
    assertEquals(1, writer.size());
    assertEquals(2, writer.get(0).size());
    verifyNoMoreInteractions(scopeListener, traceInterceptor);
    clearInvocations(scopeListener, traceInterceptor);

    firstScope.close();

    // then: 0 * _
    verifyNoMoreInteractions(scopeListener, traceInterceptor);
  }

  @Test
  @WithConfig(key = "trace.scope.strict.mode", value = "true")
  void closingScopeWhenNotOnTopInStrictMode() throws Exception {
    DDTracer strictTracer = DDTracer.builder().writer(writer).build();
    strictTracer.addTraceInterceptor(traceInterceptor);
    strictTracer.getInternalTracer().addScopeListener(scopeListener);
    // clear priority() interaction from addTraceInterceptor
    clearInvocations(traceInterceptor, scopeListener);

    Span firstSpan = strictTracer.buildSpan("someOperation").start();
    Scope firstScope = strictTracer.activateSpan(firstSpan);
    Span secondSpan = strictTracer.buildSpan("someOperation").start();
    Scope secondScope = strictTracer.activateSpan(secondSpan);

    // then: 2 * scopeListener.afterScopeActivated(), 0 * _
    verify(scopeListener, times(2)).afterScopeActivated();
    verifyNoMoreInteractions(scopeListener, traceInterceptor);
    clearInvocations(scopeListener, traceInterceptor);

    firstSpan.finish();
    assertThrows(RuntimeException.class, firstScope::close);

    // then: thrown(RuntimeException), 0 * _
    verifyNoMoreInteractions(scopeListener, traceInterceptor);
    clearInvocations(scopeListener, traceInterceptor);

    secondSpan.finish();
    secondScope.close();
    writer.waitForTraces(1);

    // then: 1 * scopeListener.afterScopeClosed(), 1 * traceInterceptor.onTraceComplete(...)
    // 1 * scopeListener.afterScopeActivated() (scope restoration after strict mode exception)
    verify(scopeListener).afterScopeClosed();
    verify(traceInterceptor).onTraceComplete(any());
    assertEquals(1, writer.size());
    assertEquals(2, writer.get(0).size());
    verify(scopeListener).afterScopeActivated();
    verifyNoMoreInteractions(scopeListener, traceInterceptor);
    clearInvocations(scopeListener, traceInterceptor);

    firstScope.close();

    verify(scopeListener).afterScopeClosed();
    verifyNoMoreInteractions(scopeListener, traceInterceptor);

    strictTracer.close();
  }

  @Test
  void injectAndExtractContext() throws Exception {
    TextMapAdapter textMap = new TextMapAdapter(new HashMap<String, String>());

    Span testSpan =
        tracer.buildSpan("clientOperation").withServiceName("someClientService").start();
    Scope scope = tracer.activateSpan(testSpan);

    tracer.inject(testSpan.context(), Format.Builtin.HTTP_HEADERS, textMap);

    SpanContext extractedContext = tracer.extract(Format.Builtin.HTTP_HEADERS, textMap);
    Span serverSpan =
        tracer
            .buildSpan("serverOperation")
            .withServiceName("someService")
            .asChildOf(extractedContext)
            .start();
    tracer.activateSpan(serverSpan).close();
    serverSpan.finish();

    scope.close();
    testSpan.finish();
    writer.waitForTraces(2);

    verify(traceInterceptor, times(2)).onTraceComplete(any());
    assertEquals(extractedContext.toTraceId(), testSpan.context().toTraceId());
    assertEquals(extractedContext.toSpanId(), testSpan.context().toSpanId());

    assertEquals(2, writer.size());
    // sort traces by start time to get deterministic order (client started first)
    List<List<DDSpan>> sortedTraces = new java.util.ArrayList<>(writer);
    sortedTraces.sort(Comparator.comparingLong(t -> t.get(0).getStartTime()));
    DDSpan clientSpan = sortedTraces.get(0).get(0);
    DDSpan serverSpanDD = sortedTraces.get(1).get(0);
    assertEquals("someClientService", clientSpan.getServiceName());
    assertEquals("clientOperation", clientSpan.getOperationName().toString());
    assertEquals("clientOperation", clientSpan.getResourceName().toString());
    assertEquals("someService", serverSpanDD.getServiceName());
    assertEquals("serverOperation", serverSpanDD.getOperationName().toString());
    assertEquals("serverOperation", serverSpanDD.getResourceName().toString());
    assertEquals(clientSpan.spanContext().getSpanId(), serverSpanDD.getParentId());
  }

  @Test
  void tolerateNullSpanActivation() throws Exception {
    try {
      Scope s = tracer.scopeManager().activate(null);
      if (s != null) s.close();
    } catch (Exception ignored) {
    }

    try {
      Scope s = tracer.activateSpan(null);
      if (s != null) s.close();
    } catch (Exception ignored) {
    }

    // make sure scope stack has been left in a valid state
    Span testSpan = tracer.buildSpan("someOperation").withServiceName("someService").start();
    Scope testScope = tracer.scopeManager().activate(testSpan);
    testSpan.finish();
    testScope.close();
    writer.waitForTraces(1);

    verify(traceInterceptor).onTraceComplete(any());

    assertEquals(1, writer.size());
    List<DDSpan> trace = writer.get(0);
    assertEquals(1, trace.size());
    DDSpan span = trace.get(0);
    assertEquals("someService", span.getServiceName());
    assertEquals("someOperation", span.getOperationName().toString());
    assertEquals("someOperation", span.getResourceName().toString());
  }
}
