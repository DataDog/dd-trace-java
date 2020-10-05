package datadog.trace.core.scopemanager

import com.timgroup.statsd.StatsDClient
import datadog.trace.agent.test.utils.ThreadUtils
import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.api.interceptor.TraceInterceptor
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentSpan
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.ScopeListener
import datadog.trace.context.TraceScope
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.util.test.DDSpecification
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Timeout

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.core.scopemanager.EventCountingListener.EVENT.ACTIVATE
import static datadog.trace.core.scopemanager.EventCountingListener.EVENT.CLOSE
import static datadog.trace.util.gc.GCUtils.awaitGC
import static java.util.concurrent.TimeUnit.SECONDS

class ScopeManagerTest extends DDSpecification {

  CountDownLatch latch
  ListWriter writer
  CoreTracer tracer
  ContinuableScopeManager scopeManager
  StatsDClient statsDClient
  EventCountingListener eventCountingListener

  def setup() {
    latch = new CountDownLatch(1)
    final currentLatch = latch
    writer = new ListWriter() {
      void incrementTraceCount() {
        currentLatch.countDown()
      }
    }
    statsDClient = Mock()
    tracer = CoreTracer.builder().writer(writer).statsDClient(statsDClient).build()
    scopeManager = tracer.scopeManager
    eventCountingListener = new EventCountingListener()
    scopeManager.addScopeListener(eventCountingListener)
  }

  def cleanup() {
    scopeManager.tlsScopeStack.get().clear()
  }

  def "non-ddspan activation results in a continuable scope"() {
    when:
    def scope = scopeManager.activate(NoopAgentSpan.INSTANCE, ScopeSource.INSTRUMENTATION)

    then:
    scopeManager.active() == scope
    scope instanceof ContinuableScopeManager.ContinuableScope

    when:
    scope.close()

    then:
    scopeManager.active() == null
  }

  def "threadlocal is empty"() {
    setup:
    def builder = tracer.buildSpan("test")
    builder.start()

    expect:
    scopeManager.active() == null
    writer.empty
  }

  def "threadlocal is active"() {
    when:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)

    then:
    !spanFinished(scope.span())
    scopeManager.active() == scope
    scope instanceof ContinuableScopeManager.ContinuableScope
    writer.empty

    when:
    scope.close()

    then:
    !spanFinished(scope.span())
    writer == []
    scopeManager.active() == null

    when:
    span.finish()
    writer.waitForTraces(1)

    then:
    spanFinished(scope.span())
    writer == [[scope.span()]]
    scopeManager.active() == null
  }

  def "sets parent as current upon close"() {
    setup:
    def parentSpan = tracer.buildSpan("parent").start()
    def parentScope = tracer.activateSpan(parentSpan)
    def childSpan = noopChild ? NoopAgentSpan.INSTANCE : tracer.buildSpan("child").start()
    def childScope = tracer.activateSpan(childSpan)

    expect:
    scopeManager.active() == childScope
    noopChild || childScope.span().context().parentId == parentScope.span().context().spanId
    noopChild || childScope.span().context().trace == parentScope.span().context().trace

    when:
    childScope.close()

    then:
    scopeManager.active() == parentScope
    noopChild || !spanFinished(childScope.span())
    !spanFinished(parentScope.span())
    writer == []

    where:
    noopChild | _
    false     | _
    true      | _
  }

  def "setAsyncPropagation is ignored"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)
    scope.setAsyncPropagation(asyncPropagating) // ignored

    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()

    expect:
    continuation != null

    cleanup:
    continuation.cancel()

    where:
    asyncPropagating | concurrent
    true             | true
    true             | false
    false            | true
    false            | false
  }

  def "Continuation.cancel doesn't close parent scope"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ContinuableScopeManager.ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()

    when:
    continuation.cancel()

    then:
    scopeManager.active() == scope

    where:
    concurrent << [false, true]
  }

  @Timeout(value = 10, unit = SECONDS)
  def "test continuation doesn't have hard reference on scope"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scopeRef = new AtomicReference<AgentScope>(tracer.activateSpan(span))
    scopeRef.get().setAsyncPropagation(true)
    def continuation = concurrent ? scopeRef.get().captureConcurrent() : scopeRef.get().capture()
    scopeRef.get().close()

    expect:
    scopeManager.active() == null

    when:
    def ref = new WeakReference<AgentScope>(scopeRef.get())
    scopeRef.set(null)
    awaitGC(ref)

    then:
    continuation != null
    ref.get() == null
    !spanFinished(span)
    writer == []

    where:
    concurrent << [false, true]
  }

  @Timeout(value = 60, unit = SECONDS)
  def "hard reference on continuation prevents trace from reporting"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ContinuableScopeManager.ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()
    scope.close()
    span.finish()

    expect:
    scopeManager.active() == null
    spanFinished(span)
    writer == []

    when:
    if (forceGC) {
      def continuationRef = new WeakReference<>(continuation)
      continuation = null // Continuation references also hold up traces.
      awaitGC(continuationRef)
      latch.await(60, SECONDS)
    }
    if (autoClose) {
      if (continuation != null) {
        continuation.cancel()
        writer.waitForTraces(1)
      }
    }

    then:
    forceGC ? true : writer == [[span]]

    where:
    autoClose | forceGC | concurrent
    true      | true    | false
    true      | false   | false
    false     | true    | false
    true      | true    | true
    true      | false   | true
    false     | true    | true
  }

  def "continuation restores trace"() {
    setup:
    def parentSpan = tracer.buildSpan("parent").start()
    def parentScope = tracer.activateSpan(parentSpan)
    def childSpan = tracer.buildSpan("child").start()
    ContinuableScopeManager.ContinuableScope childScope = (ContinuableScopeManager.ContinuableScope) tracer.activateSpan(childSpan)
    childScope.setAsyncPropagation(true)

    def continuation = concurrentChild ? childScope.captureConcurrent() : childScope.capture()
    childScope.close()

    expect:
    parentSpan.context().trace == childSpan.context().trace
    scopeManager.active() == parentScope
    !spanFinished(childSpan)
    !spanFinished(parentSpan)

    when:
    parentScope.close()
    parentSpan.finish()
    // parent span is finished, but trace is not reported

    then:
    scopeManager.active() == null
    !spanFinished(childSpan)
    spanFinished(parentSpan)
    writer == []

    when:
    def newScope = continuation.activate()
    if (concurrentChild) {
      continuation.cancel()
    }
    if (!concurrentChild) {
      newScope.setAsyncPropagation(true)
    }
    def newContinuation = concurrentNew ? newScope.captureConcurrent() : newScope.capture()

    then:
    newScope instanceof ContinuableScopeManager.ContinuableScope
    newScope.isAsyncPropagating()
    scopeManager.active() == newScope
    newScope != childScope && newScope != parentScope
    newScope.span() == childSpan
    !spanFinished(childSpan)
    spanFinished(parentSpan)
    writer == []

    when:
    newScope.close()
    newContinuation.activate().close()
    if (concurrentNew) {
      newContinuation.cancel()
    }
    childSpan.finish()
    writer.waitForTraces(1)

    then:
    scopeManager.active() == null
    spanFinished(childSpan)
    spanFinished(parentSpan)
    writer == [[childSpan, parentSpan]]

    where:
    concurrentChild | concurrentNew
    false           | false
    true            | false
    false           | true
    true            | true
  }

  def "continuation allows adding spans even after other spans were completed"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ContinuableScopeManager.ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()
    scope.close()
    span.finish()

    def newScope = continuation.activate()
    if (concurrent) {
      continuation.cancel()
    }

    expect:
    newScope instanceof ContinuableScopeManager.ContinuableScope
    newScope != scope
    scopeManager.active() == newScope
    spanFinished(span)
    writer == []

    when:
    def childSpan = tracer.buildSpan("child").start()
    def childScope = tracer.activateSpan(childSpan)
    childScope.close()
    childSpan.finish()
    scopeManager.active().close()
    writer.waitForTraces(1)

    then:
    scopeManager.active() == null
    spanFinished(childSpan)
    childSpan.context().parentId == span.context().spanId
    writer == [[childSpan, span]]

    where:
    concurrent << [false, true]
  }

  def "DDScope put in threadLocal after continuation activation"() {
    setup:
    def span = tracer.buildSpan("parent").start()
    ContinuableScopeManager.ContinuableScope scope = (ContinuableScopeManager.ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)

    expect:
    scopeManager.active() == scope

    when:
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()
    scope.close()

    then:
    scopeManager.active() == null

    when:
    def newScope = continuation.activate()
    if (concurrent) {
      continuation.cancel()
    }

    then:
    newScope != scope
    scopeManager.active() == newScope

    where:
    concurrent << [false, true]
  }

  def "test activating same span multiple times"() {
    when:
    AgentScope scope1 = scopeManager.activate(NoopAgentSpan.INSTANCE, ScopeSource.INSTRUMENTATION)

    then:
    eventCountingListener.events == [ACTIVATE]

    when:
    AgentScope scope2 = scopeManager.activate(NoopAgentSpan.INSTANCE, ScopeSource.INSTRUMENTATION)

    then: 'Activating the same span multiple times does not create a new scope'
    eventCountingListener.events == [ACTIVATE]

    when:
    scope2.close()

    then: 'Closing a scope once that has been activated multiple times does not close'
    eventCountingListener.events == [ACTIVATE]

    when:
    scope1.close()

    then:
    eventCountingListener.events == [ACTIVATE, CLOSE]
  }

  def "opening and closing multiple scopes"() {
    when:
    AgentSpan span = tracer.buildSpan("foo").start()
    AgentScope continuableScope = tracer.activateSpan(span)

    then:
    continuableScope instanceof ContinuableScopeManager.ContinuableScope
    eventCountingListener.events == [ACTIVATE]

    when:
    AgentSpan childSpan = tracer.buildSpan("foo").start()
    AgentScope childDDScope = tracer.activateSpan(childSpan)

    then:
    childDDScope instanceof ContinuableScopeManager.ContinuableScope
    eventCountingListener.events == [ACTIVATE, ACTIVATE]

    when:
    childDDScope.close()
    childSpan.finish()

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE, CLOSE, ACTIVATE]

    when:
    continuableScope.close()
    span.finish()

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE, CLOSE, ACTIVATE, CLOSE]
  }

  def "closing scope out of order - simple"() {
    when:
    AgentSpan firstSpan = tracer.buildSpan("foo").start()
    AgentScope firstScope = tracer.activateSpan(firstSpan)

    AgentSpan secondSpan = tracer.buildSpan("foo").start()
    AgentScope secondScope = tracer.activateSpan(secondSpan)

    firstSpan.finish()
    firstScope.close()

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE]
    1 * statsDClient.incrementCounter("scope.close.error")
    0 * _

    when:
    secondSpan.finish()
    secondScope.close()

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE, CLOSE, CLOSE]
    0 * _

    when:
    firstScope.close()

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE, CLOSE, CLOSE]
    1 * statsDClient.incrementCounter("scope.close.error")
  }

  def "closing scope out of order - complex"() {
    // Events are checked twice in each case to ensure a call to
    // tracer.activeScope() or tracer.activeSpan() doesn't change the count

    when:
    AgentSpan firstSpan = tracer.buildSpan("foo").start()
    AgentScope firstScope = tracer.activateSpan(firstSpan)

    then:
    eventCountingListener.events == [ACTIVATE]

    tracer.activeSpan() == firstSpan
    tracer.activeScope() == firstScope
    eventCountingListener.events == [ACTIVATE]
    0 * _

    when:
    AgentSpan secondSpan = tracer.buildSpan("bar").start()
    AgentScope secondScope = tracer.activateSpan(secondSpan)

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE]
    tracer.activeSpan() == secondSpan
    tracer.activeScope() == secondScope
    eventCountingListener.events == [ACTIVATE, ACTIVATE]
    0 * _

    when:
    AgentSpan thirdSpan = tracer.buildSpan("quux").start()
    AgentScope thirdScope = tracer.activateSpan(thirdSpan)

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE, ACTIVATE]
    tracer.activeSpan() == thirdSpan
    tracer.activeScope() == thirdScope
    eventCountingListener.events == [ACTIVATE, ACTIVATE, ACTIVATE]
    0 * _

    when:
    secondScope.close()

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE, ACTIVATE]
    tracer.activeSpan() == thirdSpan
    tracer.activeScope() == thirdScope
    eventCountingListener.events == [ACTIVATE, ACTIVATE, ACTIVATE]
    1 * statsDClient.incrementCounter("scope.close.error")
    0 * _

    when:
    thirdScope.close()

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE]
    tracer.activeSpan() == firstSpan
    tracer.activeScope() == firstScope

    eventCountingListener.events == [ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE]
    0 * _

    when:
    firstScope.close()

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE, CLOSE]
    tracer.activeScope() == null
    eventCountingListener.events == [ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE, CLOSE]
    0 * _
  }

  def "closing scope out of order - multiple activations"() {
    when:
    AgentScope scope1 = scopeManager.activate(NoopAgentSpan.INSTANCE, ScopeSource.INSTRUMENTATION)

    then:
    eventCountingListener.events == [ACTIVATE]

    when:
    AgentScope scope2 = scopeManager.activate(NoopAgentSpan.INSTANCE, ScopeSource.INSTRUMENTATION)

    then: 'Activating the same span multiple times does not create a new scope'
    eventCountingListener.events == [ACTIVATE]

    when:
    AgentSpan thirdSpan = tracer.buildSpan("quux").start()
    AgentScope thirdScope = tracer.activateSpan(thirdSpan)
    0 * _

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE]
    tracer.activeSpan() == thirdSpan
    tracer.activeScope() == thirdScope
    eventCountingListener.events == [ACTIVATE, ACTIVATE]
    0 * _

    when:
    scope2.close()

    then: 'Closing a scope once that has been activated multiple times does not close'
    eventCountingListener.events == [ACTIVATE, ACTIVATE]
    1 * statsDClient.incrementCounter("scope.close.error")
    0 * _

    when:
    thirdScope.close()
    thirdSpan.finish()

    then: 'Closing scope above multiple activated scope does not close it'
    eventCountingListener.events == [ACTIVATE, ACTIVATE, CLOSE, ACTIVATE]
    0 * _

    when:
    scope1.close()

    then:
    eventCountingListener.events == [ACTIVATE, ACTIVATE, CLOSE, ACTIVATE, CLOSE]
  }

  @Timeout(value = 60, unit = SECONDS)
  def "Closing a continued scope out of order cancels the continuation"() {
    when:
    def span = tracer.buildSpan("test").start()
    def scope = (ContinuableScopeManager.ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()
    scope.close()
    span.finish()

    then:
    scopeManager.active() == null
    spanFinished(span)
    writer == []

    when:
    def continuedScope = continuation.activate()
    AgentSpan secondSpan = tracer.buildSpan("test2").start()
    AgentScope secondScope = (ContinuableScopeManager.ContinuableScope) tracer.activateSpan(secondSpan)
    if (concurrent) {
      continuation.cancel()
    }

    then:
    scopeManager.active() == secondScope

    when:
    continuedScope.close()

    then:
    scopeManager.active() == secondScope
    writer == []

    when:
    secondScope.close()
    secondSpan.finish()
    writer.waitForTraces(1)

    then:
    writer == [[secondSpan, span]]

    where:
    concurrent << [false, true]
  }

  @Ignore
  // TraceInterceptors are called off-thread now
  def "exception thrown in TraceInterceptor does not leave scope manager in bad state"() {
    when:
    tracer.addTraceInterceptor(new ExceptionThrowingInterceptor())

    def span = tracer.buildSpan("test").start()
    def scope = (ContinuableScopeManager.ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()
    scope.close()
    span.finish()

    then:
    scopeManager.active() == null
    spanFinished(span)

    when:
    def continuedScope = continuation.activate()
    if (concurrent) {
      continuation.cancel()
    }
    continuedScope.close()

    then:
    thrown(RuntimeException)
    scopeManager.active() == null
    scopeManager.scopeStack().depth() == 0
    writer == []

    where:
    concurrent << [false, true]
  }

  @Shared
  TraceScope.Continuation continuation = null

  @Shared
  AtomicInteger iteration = new AtomicInteger(0)

  def "concurrent continuation can be activated and closed in multiple threads"() {
    setup:
    long sendDelayNanos = TimeUnit.MILLISECONDS.toNanos(500 - 100)

    when:
    def span = tracer.buildSpan("test").start()
    def start = System.nanoTime()
    def scope = (ContinuableScopeManager.ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    continuation = scope.captureConcurrent()
    scope.close()
    span.finish()

    then:
    ThreadUtils.runConcurrently(8, 512) {
      int iter = iteration.incrementAndGet()
      if (iter & 1) {
        Thread.sleep(1)
      }
      TraceScope s = continuation.activate()
      assert scopeManager.active() == s
      if (iter & 2) {
        Thread.sleep(1)
      }
      s.close()
    }

    when:
    def written = writer
    def duration = System.nanoTime() - start

    then:
    // Since we can't rely on that nothing gets written to the tracer for verification,
    // we only check for empty if we are faster than the flush interval
    if (duration < sendDelayNanos) {
      assert written == []
    }

    when:
    continuation.cancel()

    then:
    writer == [[span]]
  }

  boolean spanFinished(AgentSpan span) {
    return ((DDSpan) span)?.isFinished()
  }
}

class EventCountingListener implements ScopeListener {
  enum EVENT {
    ACTIVATE, CLOSE
  }

  public final List<EVENT> events = new ArrayList<>()

  void afterScopeActivated() {
    synchronized (events) {
      events.add(ACTIVATE)
    }
  }

  @Override
  void afterScopeClosed() {
    synchronized (events) {
      events.add(CLOSE)
    }
  }
}

class ExceptionThrowingInterceptor implements TraceInterceptor {

  @Override
  Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
    throw new RuntimeException("Always throws exception")
  }

  @Override
  int priority() {
    return 55
  }
}
