package datadog.trace.core.scopemanager;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.core.scopemanager.ScopeManagerForkedTest.EVENT.ACTIVATE;
import static datadog.trace.core.scopemanager.ScopeManagerForkedTest.EVENT.CLOSE;
import static datadog.trace.test.util.GCUtils.awaitGC;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.Stateful;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.context.TraceScope;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.ScopeManagerTestBridge;
import datadog.trace.test.util.ThreadUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class ScopeManagerForkedTest extends DDCoreJavaSpecification {

  enum EVENT {
    ACTIVATE,
    CLOSE
  }

  @Override
  protected boolean useStrictTraceWrites() {
    // This tests the behavior of the relaxed pending trace implementation
    return false;
  }

  ListWriter writer;
  CoreTracer tracer;
  ContinuableScopeManager scopeManager;
  EventCountingListener eventCountingListener;
  EventCountingExtendedListener eventCountingExtendedListener;
  ProfilingContextIntegration profilingContext;
  Stateful state;

  @BeforeAll
  static void installLegacyContextManager() {
    // this test requires use of legacy context manager
    AgentTracer.installLegacyContextManager();
  }

  @BeforeEach
  void setup() {
    state = mock(Stateful.class);
    profilingContext = mock(ProfilingContextIntegration.class);
    when(profilingContext.newScopeState(any())).thenReturn(state);
    when(profilingContext.name()).thenReturn("mock");
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).profilingContextIntegration(profilingContext).build();
    AgentTracer.forceRegister(tracer);
    scopeManager = ScopeManagerTestBridge.getScopeManager(tracer);
    eventCountingListener = new EventCountingListener();
    scopeManager.addScopeListener(eventCountingListener);
    eventCountingExtendedListener = new EventCountingExtendedListener();
    scopeManager.addScopeListener(eventCountingExtendedListener);
    // Clear interactions recorded during tracer initialization so each test starts clean
    clearInvocations(profilingContext);
  }

  @AfterEach
  void cleanup() {
    tracer.close();
  }

  @Test
  void nonDdspanActivationResultsInAContinuableScope() {
    AgentScope scope = scopeManager.activateSpan(noopSpan());

    assertSame(scope, scopeManager.active());
    assertInstanceOf(ContinuableScope.class, scope);

    scope.close();

    assertNull(scopeManager.active());
  }

  @Test
  void noScopeIsActiveBeforeActivation() throws Exception {
    tracer.buildSpan("test", "test").start();

    assertNull(scopeManager.active());
    assertTrue(writer.isEmpty());
  }

  @Test
  void simpleScopeAndSpanLifecycle() throws Exception {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);

    assertSame(span, scope.span());
    assertFalse(spanFinished(scope.span()));
    assertSame(scope, scopeManager.active());
    assertInstanceOf(ContinuableScope.class, scope);
    assertTrue(writer.isEmpty());

    scope.close();

    assertFalse(spanFinished(scope.span()));
    assertTrue(writer.isEmpty());
    assertNull(scopeManager.active());

    span.finish();
    writer.waitForTraces(1);

    assertTrue(spanFinished(scope.span()));
    assertEquals(1, writer.size());
    assertSame(scope.span(), writer.get(0).get(0));
    assertNull(scopeManager.active());
  }

  @Test
  void setsParentAsCurrentUponClose() {
    AgentSpan parentSpan = tracer.buildSpan("test", "parent").start();
    AgentScope parentScope = tracer.activateSpan(parentSpan);
    AgentSpan childSpan = tracer.buildSpan("test", "child").start();
    AgentScope childScope = tracer.activateSpan(childSpan);

    assertSame(childScope, scopeManager.active());
    assertEquals(
        parentScope.span().spanContext().getSpanId(),
        ((DDSpan) childScope.span()).spanContext().getParentId());
    assertSame(
        parentScope.span().spanContext().getTraceCollector(),
        childScope.span().spanContext().getTraceCollector());

    childScope.close();

    assertSame(parentScope, scopeManager.active());
    assertFalse(spanFinished(childScope.span()));
    assertFalse(spanFinished(parentScope.span()));
    assertTrue(writer.isEmpty());
  }

  @Test
  void setsParentAsCurrentUponCloseWithNoopChild() {
    AgentSpan parentSpan = tracer.buildSpan("test", "parent").start();
    AgentScope parentScope = tracer.activateSpan(parentSpan);
    AgentSpan childSpan = noopSpan();
    AgentScope childScope = tracer.activateSpan(childSpan);

    assertSame(childScope, scopeManager.active());

    childScope.close();

    assertSame(parentScope, scopeManager.active());
    assertFalse(spanFinished(parentScope.span()));
    assertTrue(writer.isEmpty());
  }

  @Test
  void ddScopeCreatesNoOpContinuationsWhenPropagationIsNotSet() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    tracer.activateSpan(span);
    tracer.setAsyncPropagationEnabled(false);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();

    assertSame(noopContinuation(), continuation);

    tracer.setAsyncPropagationEnabled(true);
    continuation = tracer.captureActiveSpan();

    assertNotSame(noopContinuation(), continuation);
    assertNotNull(continuation);

    continuation.cancel();
  }

  @Test
  void continuationCancelDoesNotCloseParentScope() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();

    assertNotNull(continuation);

    continuation.cancel();

    assertSame(scope, scopeManager.active());
  }

  // @Flaky("awaitGC is flaky")
  @Test
  void testContinuationDoesNotHaveHardReferenceOnScope() throws InterruptedException {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    AtomicReference<AgentScope> scopeRef = new AtomicReference<>(tracer.activateSpan(span));
    AgentScope.Continuation continuation = tracer.captureActiveSpan();

    assertNotNull(continuation);

    scopeRef.get().close();

    assertNull(scopeManager.active());

    WeakReference<AgentScope> ref = new WeakReference<>(scopeRef.get());
    scopeRef.set(null);
    awaitGC(ref);

    assertNotNull(continuation);
    assertNull(ref.get());
    assertFalse(spanFinished(span));
    assertTrue(writer.isEmpty());
  }

  @ValueSource(booleans = {true, false})
  @ParameterizedTest
  void hardReferenceOnContinuationDoesNotPreventTraceFromReporting(boolean autoClose)
      throws Exception {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();

    assertNotNull(continuation);

    scope.close();
    span.finish();
    if (autoClose) {
      continuation.cancel();
    }

    assertNull(scopeManager.active());
    assertTrue(spanFinished(span));

    writer.waitForTraces(1);

    assertEquals(1, writer.size());
    assertSame(span, writer.get(0).get(0));
  }

  @Test
  void continuationRestoresTrace() throws Exception {
    AgentSpan parentSpan = tracer.buildSpan("test", "parent").start();
    AgentScope parentScope = tracer.activateSpan(parentSpan);
    AgentSpan childSpan = tracer.buildSpan("test", "child").start();
    AgentScope childScope = tracer.activateSpan(childSpan);

    AgentScope.Continuation continuation = tracer.captureActiveSpan();
    childScope.close();

    assertNotNull(continuation);
    assertSame(parentScope, scopeManager.active());
    assertFalse(spanFinished(childSpan));
    assertFalse(spanFinished(parentSpan));

    parentScope.close();
    parentSpan.finish();

    // parent span is finished, but trace is not reported
    assertNull(scopeManager.active());
    assertFalse(spanFinished(childSpan));
    assertTrue(spanFinished(parentSpan));
    assertTrue(writer.isEmpty());

    // activating the continuation
    AgentScope newScope = continuation.activate();

    // the continued scope becomes active and span state doesn't change
    assertInstanceOf(ContinuableScope.class, newScope);
    assertTrue(tracer.isAsyncPropagationEnabled());
    assertSame(newScope, scopeManager.active());
    assertNotSame(childScope, newScope);
    assertNotSame(parentScope, newScope);
    assertSame(childSpan, newScope.span());
    assertFalse(spanFinished(childSpan));
    assertTrue(spanFinished(parentSpan));
    assertTrue(writer.isEmpty());

    // creating and activating a second continuation
    AgentScope.Continuation newContinuation = tracer.captureActiveSpan();
    newScope.close();
    AgentScope secondContinuedScope = newContinuation.activate();
    secondContinuedScope.close();
    childSpan.finish();
    writer.waitForTraces(1);

    // spans are all finished and trace is reported
    assertNull(scopeManager.active());
    assertTrue(spanFinished(childSpan));
    assertTrue(spanFinished(parentSpan));
    assertEquals(1, writer.size());
    assertTrue(writer.get(0).containsAll(Arrays.asList(childSpan, parentSpan)));
  }

  @Test
  void continuationAllowsAddingSpansEvenAfterOtherSpansWereCompleted() throws Exception {
    // creating and activating a continuation
    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();
    scope.close();
    span.finish();

    AgentScope newScope = continuation.activate();

    // the continuation sets the active scope
    assertInstanceOf(ContinuableScope.class, newScope);
    assertNotSame(scope, newScope);
    assertSame(newScope, scopeManager.active());
    assertTrue(spanFinished(span));
    assertTrue(writer.isEmpty());

    // creating a new child span under a continued scope
    AgentSpan childSpan = tracer.buildSpan("test", "child").start();
    AgentScope childScope = tracer.activateSpan(childSpan);
    childScope.close();
    childSpan.finish();

    assertSame(newScope, scopeManager.active());

    scopeManager.active().close();
    writer.waitForTraces(1);

    // the child has the correct parent
    assertNull(scopeManager.active());
    assertTrue(spanFinished(childSpan));
    assertEquals(span.spanContext().getSpanId(), ((DDSpan) childSpan).spanContext().getParentId());
    assertEquals(1, writer.size());
    assertTrue(writer.get(0).containsAll(Arrays.asList(childSpan, span)));
  }

  @Test
  void testActivatingSameSpanMultipleTimes() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    Stateful localState = mock(Stateful.class);
    when(profilingContext.newScopeState(any())).thenReturn(localState);
    clearInvocations(profilingContext);

    AgentScope scope1 = scopeManager.activateSpan(span);

    assertEvents(Arrays.asList(ACTIVATE));
    verify(profilingContext, times(1)).newScopeState(any());
    clearInvocations(profilingContext);

    AgentScope scope2 = scopeManager.activateSpan(span);

    // Activating the same span multiple times does not create a new scope
    assertEvents(Arrays.asList(ACTIVATE));
    verify(profilingContext, never()).newScopeState(any());
    clearInvocations(profilingContext);

    scope2.close();

    // Closing a scope once that has been activated multiple times does not close
    assertEvents(Arrays.asList(ACTIVATE));
    verify(localState, never()).close();
    clearInvocations(localState);

    scope1.close();

    assertEvents(Arrays.asList(ACTIVATE, CLOSE));
    verify(localState, times(1)).close();
  }

  @Test
  void openingAndClosingMultipleScopes() {
    AgentSpan span = tracer.buildSpan("test", "foo").start();
    AgentScope continuableScope = tracer.activateSpan(span);

    assertInstanceOf(ContinuableScope.class, continuableScope);
    assertEvents(Arrays.asList(ACTIVATE));

    AgentSpan childSpan = tracer.buildSpan("test", "foo").start();
    AgentScope childDDScope = tracer.activateSpan(childSpan);

    assertInstanceOf(ContinuableScope.class, childDDScope);
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));

    childDDScope.close();
    childSpan.finish();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, ACTIVATE));

    continuableScope.close();
    span.finish();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, ACTIVATE, CLOSE));
  }

  @Test
  void closingScopeOutOfOrderSimple() {
    AgentSpan firstSpan = tracer.buildSpan("test", "foo").start();
    AgentScope firstScope = tracer.activateSpan(firstSpan);

    AgentSpan secondSpan = tracer.buildSpan("test", "bar").start();
    AgentScope secondScope = tracer.activateSpan(secondSpan);

    firstSpan.finish();
    firstScope.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));
    verify(profilingContext, times(1)).onRootSpanStarted(any());
    verify(profilingContext, times(1)).onAttach();
    verify(profilingContext, times(1)).encodeOperationName("foo");
    verify(profilingContext, times(1)).encodeOperationName("bar");
    verify(profilingContext, times(2)).newScopeState(any());
    verifyNoMoreInteractions(profilingContext);
    clearInvocations(profilingContext);

    secondSpan.finish();
    secondScope.close();

    verify(profilingContext, times(1)).onRootSpanFinished(any(), any());
    verify(profilingContext, times(1)).onDetach();
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, CLOSE));
    verifyNoMoreInteractions(profilingContext);
    clearInvocations(profilingContext);

    firstScope.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, CLOSE));
  }

  @Test
  void closingScopeOutOfOrderComplex() {
    // Events are checked twice in each case to ensure a call to
    // scopeManager.active() or tracer.activeSpan() doesn't change the count

    AgentSpan firstSpan = tracer.buildSpan("test", "foo").start();
    AgentScope firstScope = tracer.activateSpan(firstSpan);

    assertEvents(Arrays.asList(ACTIVATE));
    assertSame(firstSpan, tracer.activeSpan());
    assertSame(firstScope, scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE));
    verify(profilingContext, times(1)).onRootSpanStarted(any());
    verify(profilingContext, times(1)).onAttach();
    verify(profilingContext, times(1)).encodeOperationName("foo");
    verify(profilingContext, times(1)).newScopeState(any());
    verifyNoMoreInteractions(profilingContext);
    clearInvocations(profilingContext);

    AgentSpan secondSpan = tracer.buildSpan("test", "bar").start();
    AgentScope secondScope = tracer.activateSpan(secondSpan);

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));
    assertSame(secondSpan, tracer.activeSpan());
    assertSame(secondScope, scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));
    verify(profilingContext, times(1)).encodeOperationName("bar");
    verify(profilingContext, times(1)).newScopeState(any());
    verifyNoMoreInteractions(profilingContext);
    clearInvocations(profilingContext);

    AgentSpan thirdSpan = tracer.buildSpan("test", "quux").start();
    AgentScope thirdScope = tracer.activateSpan(thirdSpan);

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE));
    assertSame(thirdSpan, tracer.activeSpan());
    assertSame(thirdScope, scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE));
    verify(profilingContext, times(1)).encodeOperationName("quux");
    verify(profilingContext, times(1)).newScopeState(any());
    verifyNoMoreInteractions(profilingContext);
    clearInvocations(profilingContext);

    secondScope.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE));
    assertSame(thirdSpan, tracer.activeSpan());
    assertSame(thirdScope, scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE));
    verifyNoMoreInteractions(profilingContext);
    clearInvocations(profilingContext);

    thirdScope.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE));
    assertSame(firstSpan, tracer.activeSpan());
    assertSame(firstScope, scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE));
    verifyNoMoreInteractions(profilingContext);
    clearInvocations(profilingContext);

    firstScope.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE, CLOSE));
    assertNull(scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE, CLOSE));
    verify(profilingContext, times(1)).onDetach();
    verifyNoMoreInteractions(profilingContext);
  }

  @Test
  void closingScopeOutOfOrderMultipleActivations() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    clearInvocations(profilingContext);

    AgentScope scope1 = scopeManager.activateSpan(span);

    assertEvents(Arrays.asList(ACTIVATE));

    AgentScope scope2 = scopeManager.activateSpan(span);

    // Activating the same span multiple times does not create a new scope
    assertEvents(Arrays.asList(ACTIVATE));
    clearInvocations(profilingContext);

    AgentSpan thirdSpan = tracer.buildSpan("test", "quux").start();
    AgentScope thirdScope = tracer.activateSpan(thirdSpan);

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));
    assertSame(thirdSpan, tracer.activeSpan());
    assertSame(thirdScope, scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));
    verify(profilingContext, times(1)).encodeOperationName("quux");
    verify(profilingContext, times(1)).newScopeState(any());
    verifyNoMoreInteractions(profilingContext);
    clearInvocations(profilingContext);

    scope2.close();

    // Closing a scope once that has been activated multiple times does not close
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));
    verifyNoMoreInteractions(profilingContext);
    clearInvocations(profilingContext);

    thirdScope.close();
    thirdSpan.finish();

    // Closing scope above multiple activated scope does not close it
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, ACTIVATE));
    verifyNoMoreInteractions(profilingContext);
    clearInvocations(profilingContext);

    scope1.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, ACTIVATE, CLOSE));
  }

  @Test
  void closingAContinuedScopeOutOfOrderCancelsTheContinuation() throws Exception {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();
    scope.close();
    span.finish();

    assertNull(scopeManager.active());
    assertTrue(spanFinished(span));
    assertTrue(writer.isEmpty());

    AgentScope continuedScope = continuation.activate();

    AgentSpan secondSpan = tracer.buildSpan("test", "test2").start();
    AgentScope secondScope = (ContinuableScope) tracer.activateSpan(secondSpan);

    assertSame(secondScope, scopeManager.active());

    continuedScope.close();

    assertSame(secondScope, scopeManager.active());
    assertTrue(writer.isEmpty());

    secondScope.close();
    secondSpan.finish();
    writer.waitForTraces(1);

    assertEquals(1, writer.size());
    assertTrue(writer.get(0).containsAll(Arrays.asList(secondSpan, span)));
  }

  @Test
  void exceptionThrownInTraceInterceptorDoesNotLeaveScopeManagerInBadState() throws Exception {
    ExceptionThrowingInterceptor interceptor = new ExceptionThrowingInterceptor();
    tracer.addTraceInterceptor(interceptor);

    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);
    scope.close();
    span.finish();

    // exception is thrown in same thread
    assertTrue(interceptor.lastTrace.contains(span));

    // scopeManager in good state
    assertNull(scopeManager.active());
    assertTrue(spanFinished(span));
    assertEquals(0, scopeManager.scopeStack().depth());
    assertEquals(1, writer.size());
    assertSame(span, writer.get(0).get(0));

    // completing another scope lifecycle
    AgentSpan span2 = tracer.buildSpan("test", "test").start();
    AgentScope scope2 = tracer.activateSpan(span2);

    assertSame(scope2, scopeManager.active());

    interceptor.shouldThrowException = false;
    scope2.close();
    span2.finish();
    writer.waitForTraces(1);

    // second lifecycle gets reported
    assertNull(scopeManager.active());
    assertTrue(spanFinished(span2));
    assertEquals(0, scopeManager.scopeStack().depth());
    assertEquals(2, writer.size());
    assertSame(span2, writer.get(1).get(0));
  }

  @Test
  void
      exceptionThrownInTraceInterceptorDoesNotLeaveScopeManagerInBadStateWhenReportingThroughPendingTraceBuffer()
          throws Exception {
    ExceptionThrowingInterceptor interceptor = new ExceptionThrowingInterceptor();
    tracer.addTraceInterceptor(interceptor);

    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();
    scope.close();
    span.finish();

    assertNotNull(continuation);
    assertNull(scopeManager.active());
    assertTrue(spanFinished(span));
    assertEquals(0, scopeManager.scopeStack().depth());
    assertTrue(writer.isEmpty());

    // wait for root span to be reported from PendingTraceBuffer
    writer.waitForTraces(1);

    assertTrue(interceptor.lastTrace.contains(span));

    // scopeManager in good state
    assertNull(scopeManager.active());
    assertTrue(spanFinished(span));
    assertEquals(0, scopeManager.scopeStack().depth());
    assertEquals(1, writer.size());
    assertSame(span, writer.get(0).get(0));

    // completing another async scope lifecycle
    AgentSpan span2 = tracer.buildSpan("test", "test").start();
    AgentScope scope2 = tracer.activateSpan(span2);
    AgentScope.Continuation continuation2 = tracer.captureActiveSpan();

    assertNotNull(continuation2);
    assertSame(scope2, scopeManager.active());

    interceptor.shouldThrowException = false;
    scope2.close();
    span2.finish();

    writer.waitForTraces(2);

    // second lifecycle gets reported as well
    assertNull(scopeManager.active());
    assertTrue(spanFinished(span2));
    assertEquals(0, scopeManager.scopeStack().depth());
    assertEquals(2, writer.size());
    assertSame(span2, writer.get(1).get(0));
  }

  @Test
  void continuationCanBeActivatedAndClosedInMultipleThreads() throws Throwable {
    long sendDelayNanos = TimeUnit.MILLISECONDS.toNanos(500 - 100);

    AgentSpan span = tracer.buildSpan("test", "test").start();
    long start = System.nanoTime();
    AgentScope scope = tracer.activateSpan(span);
    AtomicReference<TraceScope.Continuation> continuation =
        new AtomicReference<>(tracer.captureActiveSpan());
    scope.close();
    span.finish();

    continuation.get().hold();

    AtomicInteger iteration = new AtomicInteger(0);
    ThreadUtils.runConcurrently(
        8,
        512,
        () -> {
          int iter = iteration.incrementAndGet();
          if ((iter & 1) != 0) {
            Thread.sleep(1);
          }
          TraceScope s = continuation.get().activate();
          assertSame(s, scopeManager.active());
          if ((iter & 2) != 0) {
            Thread.sleep(1);
          }
          s.close();
        });

    long duration = System.nanoTime() - start;

    // Since we can't rely on that nothing gets written to the tracer for verification,
    // we only check for empty if we are faster than the flush interval
    if (duration < sendDelayNanos) {
      assertTrue(writer.isEmpty());
    }

    continuation.get().cancel();

    assertEquals(1, writer.size());
    assertSame(span, writer.get(0).get(0));
  }

  @Test
  void scopeListenerShouldBeNotifiedAboutTheCurrentlyActiveScope() {
    AgentSpan span = tracer.buildSpan("test", "test").start();

    AgentScope scope = scopeManager.activateSpan(span);

    assertEvents(Arrays.asList(ACTIVATE));

    EventCountingListener listener = new EventCountingListener();

    assertEquals(0, listener.events.size());

    scopeManager.addScopeListener(listener);

    assertEquals(Arrays.asList(ACTIVATE), listener.events);

    scope.close();

    assertEvents(Arrays.asList(ACTIVATE, CLOSE));
    assertEquals(Arrays.asList(ACTIVATE, CLOSE), listener.events);
  }

  @Test
  void extendedScopeListenerShouldBeNotifiedAboutTheCurrentlyActiveScope() {
    AgentSpan span = tracer.buildSpan("test", "test").start();

    AgentScope scope = scopeManager.activateSpan(span);

    assertEvents(Arrays.asList(ACTIVATE));

    EventCountingExtendedListener listener = new EventCountingExtendedListener();

    assertEquals(0, listener.events.size());

    scopeManager.addScopeListener(listener);

    assertEquals(Arrays.asList(ACTIVATE), listener.events);

    scope.close();

    assertEvents(Arrays.asList(ACTIVATE, CLOSE));
    assertEquals(Arrays.asList(ACTIVATE, CLOSE), listener.events);
  }

  @Test
  void scopeListenerShouldNotBeNotifiedWhenThereIsNoActiveScope() {
    EventCountingListener listener = new EventCountingListener();

    assertEquals(0, listener.events.size());

    scopeManager.addScopeListener(listener);

    assertEquals(0, listener.events.size());
  }

  @TableTest({
    "scenario           | activationException | closeException",
    "no exceptions      | false               | false         ",
    "close exception    | false               | true          ",
    "activate exception | true                | false         ",
    "both exceptions    | true                | true          "
  })
  void misbehavingScopeListenerShouldNotAffectOthers(
      boolean activationException, boolean closeException) {
    ExceptionThrowingScopeListener exceptionThrowingScopeListener =
        new ExceptionThrowingScopeListener();
    exceptionThrowingScopeListener.throwOnScopeActivated = activationException;
    exceptionThrowingScopeListener.throwOnScopeClosed = closeException;

    EventCountingListener secondEventCountingListener = new EventCountingListener();
    scopeManager.addScopeListener(exceptionThrowingScopeListener);
    scopeManager.addScopeListener(secondEventCountingListener);

    AgentSpan span = tracer.buildSpan("test", "foo").start();
    AgentScope continuableScope = tracer.activateSpan(span);

    assertEvents(Arrays.asList(ACTIVATE));
    assertEquals(Arrays.asList(ACTIVATE), secondEventCountingListener.events);

    AgentSpan childSpan = tracer.buildSpan("test", "foo").start();
    AgentScope childDDScope = tracer.activateSpan(childSpan);

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));
    assertEquals(Arrays.asList(ACTIVATE, ACTIVATE), secondEventCountingListener.events);

    childDDScope.close();
    childSpan.finish();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, ACTIVATE));
    assertEquals(
        Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, ACTIVATE), secondEventCountingListener.events);

    continuableScope.close();
    span.finish();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, ACTIVATE, CLOSE));
    assertEquals(
        Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, ACTIVATE, CLOSE),
        secondEventCountingListener.events);
  }

  @Test
  void contextThreadListenerNotifiedWhenScopeActivatedOnThreadForTheFirstTime() throws Exception {
    int numThreads = 5;
    int numTasks = 20;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    // usage of an instrumented executor results in scopestack initialisation but not scope creation
    executor.submit(() -> assertNull(scopeManager.active())).get();
    // the listener is not notified
    verify(profilingContext, never()).onAttach();
    clearInvocations(profilingContext);

    // scopes activate on threads
    AgentSpan span = tracer.buildSpan("test", "foo").start();
    Future<?>[] futures = new Future[numTasks];
    for (int i = 0; i < numTasks; i++) {
      final int taskIndex = i;
      futures[i] =
          executor.submit(
              () -> {
                AgentScope scope = tracer.activateSpan(span);
                AgentSpan child = tracer.buildSpan("test", "foo" + taskIndex).start();
                AgentScope childScope = tracer.activateSpan(child);
                try {
                  Thread.sleep(100);
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                }
                childScope.close();
                scope.close();
              });
    }
    for (Future<?> future : futures) {
      future.get();
    }

    // the activation notifies the listener whenever the stack becomes non-empty
    verify(profilingContext, times(numTasks)).onAttach();

    executor.shutdown();
  }

  @Test
  void activatingASpanMergesItWithExistingContext() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    ContextKey<String> testKey = ContextKey.named("test");
    Context context = Context.root().with(testKey, "test-value");
    ContextScope contextScope = scopeManager.attach(context);

    assertSame(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    AgentScope scope = tracer.activateSpan(span);

    assertSame(scope, scopeManager.active());
    assertNotEquals(context, scopeManager.current());
    assertSame(span, scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    scope.close();

    assertSame(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    contextScope.close();

    assertNull(scopeManager.active());
    assertEquals(Context.root(), scopeManager.current());
    assertNull(scopeManager.activeSpan());
  }

  @Test
  void capturingAndContinuingASpanMergesItWithExistingContext() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    ContextKey<String> testKey = ContextKey.named("test");
    Context context = Context.root().with(testKey, "test-value");
    ContextScope contextScope = scopeManager.attach(context);

    assertSame(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    AgentScope scope = tracer.captureSpan(span).activate();

    assertSame(scope, scopeManager.active());
    assertNotEquals(context, scopeManager.current());
    assertSame(span, scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    scope.close();

    assertSame(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    contextScope.close();

    assertNull(scopeManager.active());
    assertEquals(Context.root(), scopeManager.current());
    assertNull(scopeManager.activeSpan());
  }

  @Test
  void capturingAndContinuingTheActiveSpanMergesItWithExistingContext() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    ContextKey<String> testKey = ContextKey.named("test");
    Context context = Context.root().with(testKey, "test-value");
    ContextScope contextScope = scopeManager.attach(context);

    assertSame(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    AgentScope innerScope = tracer.activateSpan(span);
    AgentScope scope = tracer.captureActiveSpan().activate();
    innerScope.close();

    assertSame(scope, scopeManager.active());
    assertNotEquals(context, scopeManager.current());
    assertSame(span, scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    scope.close();

    assertSame(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    contextScope.close();

    assertNull(scopeManager.active());
    assertEquals(Context.root(), scopeManager.current());
    assertNull(scopeManager.activeSpan());
  }

  @Test
  void rollbackStopsAtMostRecentCheckpoint() {
    AgentSpan span1 = tracer.buildSpan("test1", "test1").start();
    AgentSpan span2 = tracer.buildSpan("test2", "test2").start();
    AgentSpan span3 = tracer.buildSpan("test3", "test3").start();

    assertNull(scopeManager.activeSpan());

    tracer.checkpointActiveForRollback();
    tracer.activateSpan(span1);
    tracer.checkpointActiveForRollback();
    tracer.activateSpan(span2);
    tracer.checkpointActiveForRollback();
    tracer.activateSpan(span1);
    tracer.checkpointActiveForRollback();
    tracer.activateSpan(span2);
    tracer.checkpointActiveForRollback();
    tracer.activateSpan(span2);
    tracer.checkpointActiveForRollback();
    tracer.activateSpan(span1);
    tracer.activateSpan(span2);
    tracer.activateSpan(span3);

    assertSame(span3, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertSame(span2, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertSame(span2, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertSame(span1, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertSame(span2, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertSame(span1, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertNull(scopeManager.activeSpan());
  }

  @Test
  void contextsCanBeSwappedOutAndBack() {
    ContextKey<String> testKey = ContextKey.named("test");
    Context context1 = Context.root().with(testKey, "first-value");
    Context context2 = context1.with(testKey, "second-value");

    Context swappedOut = scopeManager.swap(Context.root());

    assertNull(scopeManager.active());
    assertEquals(Context.root(), scopeManager.current());

    scopeManager.swap(context1);

    assertNotNull(scopeManager.active());
    assertEquals(context1, scopeManager.current());

    scopeManager.swap(swappedOut);

    assertNull(scopeManager.active());
    assertEquals(Context.root(), scopeManager.current());

    ContextScope contextScope = scopeManager.attach(context1);

    assertSame(contextScope, scopeManager.active());
    assertEquals(context1, scopeManager.current());

    swappedOut = scopeManager.swap(context2);

    assertNotNull(scopeManager.active());
    assertNotSame(contextScope, scopeManager.active());
    assertEquals(context2, scopeManager.current());
    assertEquals("first-value", swappedOut.get(testKey));

    Context context3 = swappedOut.with(testKey, "third-value");
    scopeManager.swap(context3);

    assertNotNull(scopeManager.active());
    assertNotSame(contextScope, scopeManager.active());
    assertEquals(context3, scopeManager.current());

    scopeManager.swap(swappedOut);

    assertSame(contextScope, scopeManager.active());
    assertEquals(context1, scopeManager.current());

    contextScope.close();

    assertNull(scopeManager.active());
    assertEquals(Context.root(), scopeManager.current());
  }

  @Test
  void captureViaContextContinuationAPIHoldsTrace() throws Exception {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);

    // Context.current().capture() routes through ContinuableScopeManager.capture(Context)
    ContextContinuation continuation = Context.current().capture();

    scope.close();
    span.finish();
    assertTrue(writer.isEmpty()); // trace held pending continuation

    continuation.release(); // delegates to cancel(), unblocks trace reporting
    writer.waitForTraces(1);
    assertFalse(writer.isEmpty());
  }

  @Test
  void continuationResumeActivatesSpan() throws Exception {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();
    scope.close();
    span.finish();

    assertNull(scopeManager.active());
    assertTrue(writer.isEmpty()); // trace held by continuation

    // resume() delegates to activate()
    ContextScope resumedScope = continuation.resume();
    assertSame(span, scopeManager.active().span());

    resumedScope.close();
    assertNull(scopeManager.active());
    writer.waitForTraces(1);
    assertFalse(writer.isEmpty());
  }

  @Test
  void continuationReleaseIsSameAsCancel() throws Exception {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();
    scope.close();
    span.finish();

    assertTrue(writer.isEmpty()); // trace held by continuation

    continuation.release(); // delegates to cancel()
    writer.waitForTraces(1);
    assertFalse(writer.isEmpty());
  }

  @Test
  void captureContextWithoutSpanUsesNoopTraceCollector() {
    ContextKey<String> key = ContextKey.named("test-key");
    Context ctx = Context.root().with(key, "value");
    assertDoesNotThrow(
        () -> {
          // NoopAgentTraceCollector handles capture/release without throwing
          try (ContextScope scope = ctx.attach()) {
            Context.current().capture().release();
          }
        });
  }

  private boolean spanFinished(AgentSpan span) {
    return span instanceof DDSpan && ((DDSpan) span).isFinished();
  }

  private void assertEvents(List<EVENT> events) {
    assertEquals(events, eventCountingListener.events);
    assertEquals(events, eventCountingExtendedListener.events);
  }

  static class EventCountingListener implements ScopeListener {
    public final List<EVENT> events = new ArrayList<>();

    @Override
    public void afterScopeActivated() {
      synchronized (events) {
        events.add(ACTIVATE);
      }
    }

    @Override
    public void afterScopeClosed() {
      synchronized (events) {
        events.add(CLOSE);
      }
    }
  }

  static class EventCountingExtendedListener implements ExtendedScopeListener {
    public final List<EVENT> events = new ArrayList<>();

    @Override
    public void afterScopeActivated() {
      throw new IllegalArgumentException("This should not be called");
    }

    @Override
    public void afterScopeActivated(DDTraceId traceId, long spanId) {
      synchronized (events) {
        events.add(ACTIVATE);
      }
    }

    @Override
    public void afterScopeClosed() {
      synchronized (events) {
        events.add(CLOSE);
      }
    }
  }

  static class ExceptionThrowingScopeListener implements ScopeListener {
    boolean throwOnScopeActivated = false;
    boolean throwOnScopeClosed = false;

    @Override
    public void afterScopeActivated() {
      if (throwOnScopeActivated) {
        throw new RuntimeException("Exception on activated");
      }
    }

    @Override
    public void afterScopeClosed() {
      if (throwOnScopeClosed) {
        throw new RuntimeException("Exception on closed");
      }
    }
  }

  static class ExceptionThrowingInterceptor implements TraceInterceptor {
    boolean shouldThrowException = true;
    Collection<? extends MutableSpan> lastTrace;

    @Override
    public Collection<? extends MutableSpan> onTraceComplete(
        Collection<? extends MutableSpan> trace) {
      lastTrace = trace;
      if (shouldThrowException) {
        throw new RuntimeException("Always throws exception");
      } else {
        return trace;
      }
    }

    @Override
    public int priority() {
      return 55;
    }
  }
}
