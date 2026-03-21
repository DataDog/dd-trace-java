package datadog.trace.core.scopemanager;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.core.scopemanager.EVENT.ACTIVATE;
import static datadog.trace.core.scopemanager.EVENT.CLOSE;
import static datadog.trace.test.util.GCUtils.awaitGC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.Context;
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
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import datadog.trace.test.util.ThreadUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class ScopeManagerTest extends DDCoreSpecification {

  ListWriter writer;
  CoreTracer tracer;
  ContinuableScopeManager scopeManager;
  EventCountingListener eventCountingListener;
  EventCountingExtendedListener eventCountingExtendedListener;
  ProfilingContextIntegration profilingContext;

  @BeforeEach
  void setup() {
    Stateful state = mock(Stateful.class);
    profilingContext = mock(ProfilingContextIntegration.class);
    when(profilingContext.newScopeState(any())).thenReturn(state);
    when(profilingContext.name()).thenReturn("mock");
    writer = new ListWriter();
    tracer =
        tracerBuilder()
            .writer(writer)
            .profilingContextIntegration(profilingContext)
            .strictTraceWrites(false)
            .build();
    scopeManager = (ContinuableScopeManager) tracer.scopeManager;
    eventCountingListener = new EventCountingListener();
    scopeManager.addScopeListener(eventCountingListener);
    eventCountingExtendedListener = new EventCountingExtendedListener();
    scopeManager.addScopeListener(eventCountingExtendedListener);
  }

  @AfterEach
  void cleanup() {
    tracer.close();
  }

  @Test
  void nonDdspanActivationResultsInAContinuableScope() {
    AgentScope scope = scopeManager.activateSpan(noopSpan());

    assertEquals(scope, scopeManager.active());
    assertInstanceOf(ContinuableScope.class, scope);

    scope.close();

    assertNull(scopeManager.active());
  }

  @Test
  void noScopeIsActiveBeforeActivation() {
    tracer.buildSpan("test", "test").start();

    assertNull(scopeManager.active());
    assertTrue(writer.isEmpty());
  }

  @Test
  void simpleScopeAndSpanLifecycle() throws Exception {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    AgentScope scope = tracer.activateSpan(span);

    assertEquals(span, scope.span());
    assertFalse(spanFinished(scope.span()));
    assertEquals(scope, scopeManager.active());
    assertInstanceOf(ContinuableScope.class, scope);
    assertTrue(writer.isEmpty());

    scope.close();

    assertFalse(spanFinished(scope.span()));
    assertEquals(Collections.emptyList(), writer);
    assertNull(scopeManager.active());

    span.finish();
    writer.waitForTraces(1);

    assertTrue(spanFinished(scope.span()));
    assertEquals(Collections.singletonList(Collections.singletonList(scope.span())), writer);
    assertNull(scopeManager.active());
  }

  @Test
  void setsParentAsCurrentUponClose() {
    AgentSpan parentSpan = tracer.buildSpan("test", "parent").start();
    AgentScope parentScope = tracer.activateSpan(parentSpan);
    AgentSpan childSpan = tracer.buildSpan("test", "child").start();
    AgentScope childScope = tracer.activateSpan(childSpan);

    assertEquals(childScope, scopeManager.active());
    assertEquals(
        parentScope.span().context().getSpanId(),
        ((DDSpanContext) childScope.span().context()).getParentId());
    assertEquals(
        ((DDSpanContext) parentScope.span().context()).getTraceCollector(),
        ((DDSpanContext) childScope.span().context()).getTraceCollector());

    childScope.close();

    assertEquals(parentScope, scopeManager.active());
    assertFalse(spanFinished(childScope.span()));
    assertFalse(spanFinished(parentScope.span()));
    assertEquals(Collections.emptyList(), writer);
  }

  @Test
  void setsParentAsCurrentUponCloseWithNoopChild() {
    AgentSpan parentSpan = tracer.buildSpan("test", "parent").start();
    AgentScope parentScope = tracer.activateSpan(parentSpan);
    AgentSpan childSpan = noopSpan();
    AgentScope childScope = tracer.activateSpan(childSpan);

    assertEquals(childScope, scopeManager.active());

    childScope.close();

    assertEquals(parentScope, scopeManager.active());
    assertFalse(spanFinished(parentScope.span()));
    assertEquals(Collections.emptyList(), writer);
  }

  @Test
  void ddScopeCreatesNoOpContinuationsWhenPropagationIsNotSet() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    tracer.activateSpan(span);
    tracer.setAsyncPropagationEnabled(false);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();

    assertEquals(noopContinuation(), continuation);

    tracer.setAsyncPropagationEnabled(true);
    continuation = tracer.captureActiveSpan();

    assertNotEquals(noopContinuation(), continuation);
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

    assertEquals(scope, scopeManager.active());
  }

  @Test
  void testContinuationDoesNotHaveHardReferenceOnScope() throws Exception {
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
    assertEquals(Collections.emptyList(), writer);
  }

  @TableTest({
    "scenario   | autoClose",
    "auto close | true     ",
    "no close   | false    "
  })
  @ParameterizedTest(
      name = "[{index}] hard reference on continuation does not prevent trace from reporting")
  void hardReferenceOnContinuationDoesNotPreventTraceFromReporting(
      String scenario, boolean autoClose) throws Exception {
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

    assertEquals(Collections.singletonList(Collections.singletonList(span)), writer);
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
    assertEquals(parentScope, scopeManager.active());
    assertFalse(spanFinished(childSpan));
    assertFalse(spanFinished(parentSpan));

    parentScope.close();
    parentSpan.finish();

    // parent span is finished, but trace is not reported
    assertNull(scopeManager.active());
    assertFalse(spanFinished(childSpan));
    assertTrue(spanFinished(parentSpan));
    assertEquals(Collections.emptyList(), writer);

    // activating the continuation
    AgentScope newScope = continuation.activate();

    // the continued scope becomes active and span state doesnt change
    assertInstanceOf(ContinuableScope.class, newScope);
    assertTrue(tracer.isAsyncPropagationEnabled());
    assertEquals(newScope, scopeManager.active());
    assertNotEquals(childScope, newScope);
    assertNotEquals(parentScope, newScope);
    assertEquals(childSpan, newScope.span());
    assertFalse(spanFinished(childSpan));
    assertTrue(spanFinished(parentSpan));
    assertEquals(Collections.emptyList(), writer);

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
    assertEquals(Collections.singletonList(Arrays.asList(childSpan, parentSpan)), writer);
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
    assertNotEquals(scope, newScope);
    assertEquals(newScope, scopeManager.active());
    assertTrue(spanFinished(span));
    assertEquals(Collections.emptyList(), writer);

    // creating a new child span under a continued scope
    AgentSpan childSpan = tracer.buildSpan("test", "child").start();
    AgentScope childScope = tracer.activateSpan(childSpan);
    childScope.close();
    childSpan.finish();

    assertEquals(newScope, scopeManager.active());

    scopeManager.active().close();
    writer.waitForTraces(1);

    // the child has the correct parent
    assertNull(scopeManager.active());
    assertTrue(spanFinished(childSpan));
    assertEquals(span.context().getSpanId(), ((DDSpanContext) childSpan.context()).getParentId());
    assertEquals(Collections.singletonList(Arrays.asList(childSpan, span)), writer);
  }

  @Test
  void testActivatingSameSpanMultipleTimes() {
    AgentSpan span = tracer.buildSpan("test", "test").start();

    AgentScope scope1 = scopeManager.activateSpan(span);

    assertEvents(Collections.singletonList(ACTIVATE));

    AgentScope scope2 = scopeManager.activateSpan(span);

    // Activating the same span multiple times does not create a new scope
    assertEvents(Collections.singletonList(ACTIVATE));

    scope2.close();

    // Closing a scope once that has been activated multiple times does not close
    assertEvents(Collections.singletonList(ACTIVATE));

    scope1.close();

    assertEvents(Arrays.asList(ACTIVATE, CLOSE));
  }

  @Test
  void openingAndClosingMultipleScopes() {
    AgentSpan span = tracer.buildSpan("test", "foo").start();
    AgentScope continuableScope = tracer.activateSpan(span);

    assertInstanceOf(ContinuableScope.class, continuableScope);
    assertEvents(Collections.singletonList(ACTIVATE));

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

    secondSpan.finish();
    secondScope.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, CLOSE));

    firstScope.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, CLOSE));
  }

  @Test
  void closingScopeOutOfOrderComplex() {
    // Events are checked twice in each case to ensure a call to
    // scopeManager.active() or tracer.activeSpan() doesn't change the count

    AgentSpan firstSpan = tracer.buildSpan("test", "foo").start();
    AgentScope firstScope = tracer.activateSpan(firstSpan);

    assertEvents(Collections.singletonList(ACTIVATE));
    assertEquals(firstSpan, tracer.activeSpan());
    assertEquals(firstScope, scopeManager.active());
    assertEvents(Collections.singletonList(ACTIVATE));

    AgentSpan secondSpan = tracer.buildSpan("test", "bar").start();
    AgentScope secondScope = tracer.activateSpan(secondSpan);

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));
    assertEquals(secondSpan, tracer.activeSpan());
    assertEquals(secondScope, scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));

    AgentSpan thirdSpan = tracer.buildSpan("test", "quux").start();
    AgentScope thirdScope = tracer.activateSpan(thirdSpan);

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE));
    assertEquals(thirdSpan, tracer.activeSpan());
    assertEquals(thirdScope, scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE));

    secondScope.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE));
    assertEquals(thirdSpan, tracer.activeSpan());
    assertEquals(thirdScope, scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE));

    thirdScope.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE));
    assertEquals(firstSpan, tracer.activeSpan());
    assertEquals(firstScope, scopeManager.active());

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE));

    firstScope.close();

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE, CLOSE));
    assertNull(scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE, CLOSE));
  }

  @Test
  void closingScopeOutOfOrderMultipleActivations() {
    AgentSpan span = tracer.buildSpan("test", "test").start();

    AgentScope scope1 = scopeManager.activateSpan(span);

    assertEvents(Collections.singletonList(ACTIVATE));

    AgentScope scope2 = scopeManager.activateSpan(span);

    // Activating the same span multiple times does not create a new scope
    assertEvents(Collections.singletonList(ACTIVATE));

    AgentSpan thirdSpan = tracer.buildSpan("test", "quux").start();
    AgentScope thirdScope = tracer.activateSpan(thirdSpan);

    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));
    assertEquals(thirdSpan, tracer.activeSpan());
    assertEquals(thirdScope, scopeManager.active());
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));

    scope2.close();

    // Closing a scope once that has been activated multiple times does not close
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE));

    thirdScope.close();
    thirdSpan.finish();

    // Closing scope above multiple activated scope does not close it
    assertEvents(Arrays.asList(ACTIVATE, ACTIVATE, CLOSE, ACTIVATE));

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
    assertEquals(Collections.emptyList(), writer);

    AgentScope continuedScope = continuation.activate();

    AgentSpan secondSpan = tracer.buildSpan("test", "test2").start();
    AgentScope secondScope = (ContinuableScope) tracer.activateSpan(secondSpan);

    assertEquals(secondScope, scopeManager.active());

    continuedScope.close();

    assertEquals(secondScope, scopeManager.active());
    assertEquals(Collections.emptyList(), writer);

    secondScope.close();
    secondSpan.finish();
    writer.waitForTraces(1);

    assertEquals(Collections.singletonList(Arrays.asList(secondSpan, span)), writer);
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
    assertEquals(Collections.singletonList(span), interceptor.lastTrace);

    // scopeManager in good state
    assertNull(scopeManager.active());
    assertTrue(spanFinished(span));
    assertEquals(0, scopeManager.scopeStack().depth());
    assertEquals(Collections.singletonList(Collections.singletonList(span)), writer);

    // completing another scope lifecycle
    AgentSpan span2 = tracer.buildSpan("test", "test").start();
    AgentScope scope2 = tracer.activateSpan(span2);

    assertEquals(scope2, scopeManager.active());

    interceptor.shouldThrowException = false;
    scope2.close();
    span2.finish();
    writer.waitForTraces(1);

    // second lifecycle gets reported
    assertNull(scopeManager.active());
    assertTrue(spanFinished(span2));
    assertEquals(0, scopeManager.scopeStack().depth());
    assertEquals(
        Arrays.asList(Collections.singletonList(span), Collections.singletonList(span2)), writer);
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
    assertEquals(Collections.emptyList(), writer);

    // wait for root span to be reported from PendingTraceBuffer
    writer.waitForTraces(1);

    assertEquals(Collections.singletonList(span), interceptor.lastTrace);

    // scopeManager in good state
    assertNull(scopeManager.active());
    assertTrue(spanFinished(span));
    assertEquals(0, scopeManager.scopeStack().depth());
    assertEquals(Collections.singletonList(Collections.singletonList(span)), writer);

    // completing another async scope lifecycle
    AgentSpan span2 = tracer.buildSpan("test", "test").start();
    AgentScope scope2 = tracer.activateSpan(span2);
    AgentScope.Continuation continuation2 = tracer.captureActiveSpan();

    assertNotNull(continuation2);
    assertEquals(scope2, scopeManager.active());

    interceptor.shouldThrowException = false;
    scope2.close();
    span2.finish();

    writer.waitForTraces(2);

    // second lifecycle gets reported as well
    assertNull(scopeManager.active());
    assertTrue(spanFinished(span2));
    assertEquals(0, scopeManager.scopeStack().depth());
    assertEquals(
        Arrays.asList(Collections.singletonList(span), Collections.singletonList(span2)), writer);
  }

  static AgentScope.Continuation sharedContinuation = null;
  static AtomicInteger iteration = new AtomicInteger(0);

  @Test
  void continuationCanBeActivatedAndClosedInMultipleThreads() throws Throwable {
    long sendDelayNanos = TimeUnit.MILLISECONDS.toNanos(500 - 100);

    AgentSpan span = tracer.buildSpan("test", "test").start();
    long start = System.nanoTime();
    ContinuableScope scope = (ContinuableScope) tracer.activateSpan(span);
    sharedContinuation = tracer.captureActiveSpan();
    scope.close();
    span.finish();

    sharedContinuation.hold();

    ThreadUtils.runConcurrently(
        8,
        512,
        () -> {
          int iter = iteration.incrementAndGet();
          if ((iter & 1) != 0) {
            try {
              Thread.sleep(1);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          AgentScope s = sharedContinuation.activate();
          assertEquals(s, scopeManager.active());
          if ((iter & 2) != 0) {
            try {
              Thread.sleep(1);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          s.close();
        });

    List<?> written = new ArrayList<>(writer);
    long duration = System.nanoTime() - start;

    // Since we can't rely on that nothing gets written to the tracer for verification,
    // we only check for empty if we are faster than the flush interval
    if (duration < sendDelayNanos) {
      assertEquals(Collections.emptyList(), written);
    }

    sharedContinuation.cancel();

    assertEquals(Collections.singletonList(Collections.singletonList(span)), writer);
  }

  @Test
  void scopeListenerShouldBeNotifiedAboutTheCurrentlyActiveScope() {
    AgentSpan span = tracer.buildSpan("test", "test").start();

    AgentScope scope = scopeManager.activateSpan(span);

    assertEvents(Collections.singletonList(ACTIVATE));

    EventCountingListener listener = new EventCountingListener();

    assertEquals(Collections.emptyList(), listener.events);

    scopeManager.addScopeListener(listener);

    assertEquals(Collections.singletonList(ACTIVATE), listener.events);

    scope.close();

    assertEvents(Arrays.asList(ACTIVATE, CLOSE));
    assertEquals(Arrays.asList(ACTIVATE, CLOSE), listener.events);
  }

  @Test
  void extendedScopeListenerShouldBeNotifiedAboutTheCurrentlyActiveScope() {
    AgentSpan span = tracer.buildSpan("test", "test").start();

    AgentScope scope = scopeManager.activateSpan(span);

    assertEvents(Collections.singletonList(ACTIVATE));

    EventCountingExtendedListener listener = new EventCountingExtendedListener();

    assertEquals(Collections.emptyList(), listener.events);

    scopeManager.addScopeListener(listener);

    assertEquals(Collections.singletonList(ACTIVATE), listener.events);

    scope.close();

    assertEvents(Arrays.asList(ACTIVATE, CLOSE));
    assertEquals(Arrays.asList(ACTIVATE, CLOSE), listener.events);
  }

  @Test
  void scopeListenerShouldNotBeNotifiedWhenThereIsNoActiveScope() {
    EventCountingListener listener = new EventCountingListener();

    assertEquals(Collections.emptyList(), listener.events);

    scopeManager.addScopeListener(listener);

    assertEquals(Collections.emptyList(), listener.events);
  }

  @TableTest({
    "scenario             | activationException | closeException",
    "no exceptions        | false               | false         ",
    "close exception      | false               | true          ",
    "activation exception | true                | false         ",
    "both exceptions      | true                | true          "
  })
  @ParameterizedTest(name = "[{index}] misbehaving ScopeListener should not affect others")
  void misbehavingScopeListenerShouldNotAffectOthers(
      boolean activationException, boolean closeException) {
    ExceptionThrowingScopeListener exceptionThrowingScopeLister =
        new ExceptionThrowingScopeListener();
    exceptionThrowingScopeLister.throwOnScopeActivated = activationException;
    exceptionThrowingScopeLister.throwOnScopeClosed = closeException;

    EventCountingListener secondEventCountingListener = new EventCountingListener();
    scopeManager.addScopeListener(exceptionThrowingScopeLister);
    scopeManager.addScopeListener(secondEventCountingListener);

    AgentSpan span = tracer.buildSpan("test", "foo").start();
    AgentScope continuableScope = tracer.activateSpan(span);

    assertEvents(Collections.singletonList(ACTIVATE));
    assertEquals(Collections.singletonList(ACTIVATE), secondEventCountingListener.events);

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
    executor
        .submit(
            () -> {
              assertNull(scopeManager.active());
            })
        .get();
    // the listener is not notified (no onAttach calls)

    // scopes activate on threads
    AgentSpan span = tracer.buildSpan("test", "foo").start();
    Future<?>[] futures = new Future<?>[numTasks];
    for (int i = 0; i < numTasks; i++) {
      futures[i] =
          executor.submit(
              () -> {
                AgentScope innerScope = tracer.activateSpan(span);
                AgentSpan child = tracer.buildSpan("test", "foo").start();
                AgentScope childScope = tracer.activateSpan(child);
                try {
                  Thread.sleep(100);
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                }
                childScope.close();
                innerScope.close();
              });
    }
    for (Future<?> future : futures) {
      future.get();
    }

    executor.shutdown();
  }

  @Test
  void activatingASpanMergesItWithExistingContext() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    ContextKey<String> testKey = ContextKey.named("test");
    Context context = Context.root().with(testKey, "test-value");
    ContextScope contextScope = scopeManager.attach(context);

    assertEquals(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    AgentScope scope = tracer.activateSpan(span);

    assertEquals(scope, scopeManager.active());
    assertNotEquals(context, scopeManager.current());
    assertEquals(span, scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    scope.close();

    assertEquals(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    contextScope.close();

    assertNull(scopeManager.active());
    assertEquals(Context.root(), scopeManager.current());
    assertNull(scopeManager.activeSpan());
  }

  @Test
  void capturingAndContinuingASpanMergesItWithExistingContext() throws Exception {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    ContextKey<String> testKey = ContextKey.named("test");
    Context context = Context.root().with(testKey, "test-value");
    ContextScope contextScope = scopeManager.attach(context);

    assertEquals(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    AgentScope scope = tracer.captureSpan(span).activate();

    assertEquals(scope, scopeManager.active());
    assertNotEquals(context, scopeManager.current());
    assertEquals(span, scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    scope.close();

    assertEquals(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    contextScope.close();

    assertNull(scopeManager.active());
    assertEquals(Context.root(), scopeManager.current());
    assertNull(scopeManager.activeSpan());
  }

  @Test
  void capturingAndContinuingTheActiveSpanMergesItWithExistingContext() throws Exception {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    ContextKey<String> testKey = ContextKey.named("test");
    Context context = Context.root().with(testKey, "test-value");
    ContextScope contextScope = scopeManager.attach(context);

    assertEquals(contextScope, scopeManager.active());
    assertEquals(context, scopeManager.current());
    assertNull(scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    AgentScope innerScope = tracer.activateSpan(span);
    AgentScope.Continuation cont = tracer.captureActiveSpan();
    innerScope.close();
    AgentScope scope = cont.activate();

    assertEquals(scope, scopeManager.active());
    assertNotEquals(context, scopeManager.current());
    assertEquals(span, scopeManager.activeSpan());
    assertEquals("test-value", scopeManager.current().get(testKey));

    scope.close();

    assertEquals(contextScope, scopeManager.active());
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

    assertEquals(span3, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertEquals(span2, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertEquals(span2, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertEquals(span1, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertEquals(span2, scopeManager.activeSpan());

    tracer.rollbackActiveToCheckpoint();
    assertEquals(span1, scopeManager.activeSpan());

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

    assertEquals(contextScope, scopeManager.active());
    assertEquals(context1, scopeManager.current());

    swappedOut = scopeManager.swap(context2);

    assertNotNull(scopeManager.active());
    assertNotEquals(contextScope, scopeManager.active());
    assertEquals(context2, scopeManager.current());
    assertEquals("first-value", swappedOut.get(testKey));

    Context context3 = swappedOut.with(testKey, "third-value");
    scopeManager.swap(context3);

    assertNotNull(scopeManager.active());
    assertNotEquals(contextScope, scopeManager.active());
    assertEquals(context3, scopeManager.current());

    scopeManager.swap(swappedOut);

    assertEquals(contextScope, scopeManager.active());
    assertEquals(context1, scopeManager.current());

    contextScope.close();

    assertNull(scopeManager.active());
    assertEquals(Context.root(), scopeManager.current());
  }

  private boolean spanFinished(AgentSpan span) {
    return ((DDSpan) span).isFinished();
  }

  private void assertEvents(List<EVENT> events) {
    assertEquals(events, eventCountingListener.events);
    assertEquals(events, eventCountingExtendedListener.events);
  }
}

class EventCountingListener implements ScopeListener {
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

class EventCountingExtendedListener implements ExtendedScopeListener {
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

class ExceptionThrowingScopeListener implements ScopeListener {
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

class ExceptionThrowingInterceptor implements TraceInterceptor {
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
