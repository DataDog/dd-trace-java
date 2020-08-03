package datadog.trace.core.scopemanager

import com.timgroup.statsd.StatsDClient
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentSpan
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.ScopeListener
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.util.test.DDSpecification
import spock.lang.Timeout

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.util.gc.GCUtils.awaitGC
import static java.util.concurrent.TimeUnit.SECONDS

class ScopeManagerTest extends DDSpecification {

  CountDownLatch latch
  ListWriter writer
  CoreTracer tracer
  ContinuableScopeManager scopeManager
  StatsDClient statsDClient

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
  }

  def cleanup() {
    scopeManager.tlsScopeStack.get().clear()
  }

  def "non-ddspan activation results in a continuable scope"() {
    when:
    def scope = scopeManager.activate(NoopAgentSpan.INSTANCE, ScopeSource.INSTRUMENTATION)

    then:
    scopeManager.active() == scope
    scope instanceof ScopeInterceptor.Scope

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
    scope instanceof ScopeInterceptor.Scope
    writer.empty

    when:
    scope.close()

    then:
    !spanFinished(scope.span())
    writer == []
    scopeManager.active() == null

    when:
    span.finish()

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

  def "DDScope only creates continuations when propagation is set"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ScopeInterceptor.Scope) tracer.activateSpan(span)
    def continuation = scope.capture()

    expect:
    continuation == null

    when:
    scope.setAsyncPropagation(true)
    continuation = scope.capture()
    then:
    continuation != null

    cleanup:
    continuation.cancel()
  }

  def "Continuation.cancel doesn't close parent scope"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ScopeInterceptor.Scope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()

    when:
    continuation.cancel()

    then:
    scopeManager.active() == scope
  }

  @Timeout(value = 10, unit = SECONDS)
  def "test continuation doesn't have hard reference on scope"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scopeRef = new AtomicReference<AgentScope>(tracer.activateSpan(span))
    scopeRef.get().setAsyncPropagation(true)
    def continuation = scopeRef.get().capture()
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
  }

  @Timeout(value = 60, unit = SECONDS)
  def "hard reference on continuation prevents trace from reporting"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ScopeInterceptor.Scope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()
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
      }
    }

    then:
    forceGC ? true : writer == [[span]]

    where:
    autoClose | forceGC
    true      | true
    true      | false
    false     | true
  }

  def "continuation restores trace"() {
    setup:
    def parentSpan = tracer.buildSpan("parent").start()
    def parentScope = tracer.activateSpan(parentSpan)
    def childSpan = tracer.buildSpan("child").start()
    ScopeInterceptor.Scope childScope = (ScopeInterceptor.Scope) tracer.activateSpan(childSpan)
    childScope.setAsyncPropagation(true)

    def continuation = childScope.capture()
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
    newScope.setAsyncPropagation(true)
    def newContinuation = newScope.capture()

    then:
    newScope instanceof ScopeInterceptor.Scope
    scopeManager.active() == newScope
    newScope != childScope && newScope != parentScope
    newScope.span() == childSpan
    !spanFinished(childSpan)
    spanFinished(parentSpan)
    writer == []

    when:
    newScope.close()
    newContinuation.activate().close()
    childSpan.finish()

    then:
    scopeManager.active() == null
    spanFinished(childSpan)
    spanFinished(parentSpan)
    writer == [[childSpan, parentSpan]]
  }

  def "continuation allows adding spans even after other spans were completed"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ScopeInterceptor.Scope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()
    scope.close()
    span.finish()

    def newScope = continuation.activate()

    expect:
    newScope instanceof ScopeInterceptor.Scope
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

    then:
    scopeManager.active() == null
    spanFinished(childSpan)
    childSpan.context().parentId == span.context().spanId
    writer == [[childSpan, span]]
  }

  def "DDScope put in threadLocal after continuation activation"() {
    setup:
    def span = tracer.buildSpan("parent").start()
    ScopeInterceptor.Scope scope = (ScopeInterceptor.Scope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)

    expect:
    scopeManager.active() == scope

    when:
    def cont = scope.capture()
    scope.close()

    then:
    scopeManager.active() == null

    when:
    def newScope = cont.activate()

    then:
    newScope != scope
    scopeManager.active() == newScope
  }

  def "test activating same scope multiple times"() {
    setup:
    def eventCountingLister = new EventCountingListener()
    AtomicInteger activatedCount = eventCountingLister.activatedCount
    AtomicInteger closedCount = eventCountingLister.closedCount

    scopeManager.addScopeListener(eventCountingLister)

    when:
    AgentScope scope1 = scopeManager.activate(NoopAgentSpan.INSTANCE, ScopeSource.INSTRUMENTATION)

    then:
    activatedCount.get() == 1
    closedCount.get() == 0

    when:
    AgentScope scope2 = scopeManager.activate(NoopAgentSpan.INSTANCE, ScopeSource.INSTRUMENTATION)

    then: 'Activating the same span multiple times does not create a new scope'
    activatedCount.get() == 1
    closedCount.get() == 0

    when:
    scope2.close()

    then: 'Closing a scope once that has been activated multiple times does not close'
    activatedCount.get() == 1
    closedCount.get() == 0

    when:
    scope1.close()

    then:
    activatedCount.get() == 1
    closedCount.get() == 1
  }

  def "opening and closing multiple scopes"() {
    setup:
    def eventCountingLister = new EventCountingListener()
    AtomicInteger activatedCount = eventCountingLister.activatedCount
    AtomicInteger closedCount = eventCountingLister.closedCount

    scopeManager.addScopeListener(eventCountingLister)

    when:
    AgentSpan span = tracer.buildSpan("foo").start()
    AgentScope continuableScope = tracer.activateSpan(span)

    then:
    continuableScope instanceof ScopeInterceptor.Scope
    activatedCount.get() == 1
    closedCount.get() == 0

    when:
    AgentSpan childSpan = tracer.buildSpan("foo").start()
    AgentScope childDDScope = tracer.activateSpan(childSpan)

    then:
    childDDScope instanceof ScopeInterceptor.Scope
    activatedCount.get() == 2
    closedCount.get() == 0

    when:
    childDDScope.close()
    childSpan.finish()

    then:
    activatedCount.get() == 2
    closedCount.get() == 1

    when:
    continuableScope.close()
    span.finish()

    then:
    activatedCount.get() == 2
    closedCount.get() == 2
  }

  def "scope not closed when not on top"() {
    // DQH: This test has been left unchanged from before the change to
    // make sure all listener behavior is approximately the same.

    setup:
    def eventCountingLister = new EventCountingListener()
    AtomicInteger activatedCount = eventCountingLister.activatedCount
    AtomicInteger closedCount = eventCountingLister.closedCount

    scopeManager.addScopeListener(eventCountingLister)

    when:
    AgentSpan firstSpan = tracer.buildSpan("foo").start()
    AgentScope firstScope = tracer.activateSpan(firstSpan)

    AgentSpan secondSpan = tracer.buildSpan("foo").start()
    AgentScope secondScope = tracer.activateSpan(secondSpan)

    firstSpan.finish()
    firstScope.close()

    then:
    activatedCount.get() == 2
    closedCount.get() == 0
    1 * statsDClient.incrementCounter("scope.close.error")
    0 * _

    when:
    secondSpan.finish()
    secondScope.close()

    then:
    activatedCount.get() == 2
    closedCount.get() == 1
    0 * _

    when:
    firstScope.close()

    then:
    activatedCount.get() == 2
    closedCount.get() == 2
    0 * _
  }

  def "scope closed out of order 2"() {
    when:
    AgentSpan firstSpan = tracer.buildSpan("foo").start()
    AgentScope firstScope = tracer.activateSpan(firstSpan)

    then:
    tracer.activeSpan() == firstSpan
    tracer.activeScope() == firstScope

    when:
    AgentSpan secondSpan = tracer.buildSpan("bar").start()
    AgentScope secondScope = tracer.activateSpan(secondSpan)

    then:
    tracer.activeSpan() == secondSpan
    tracer.activeScope() == secondScope

    when:
    AgentSpan thirdSpan = tracer.buildSpan("quux").start()
    AgentScope thirdScope = tracer.activateSpan(thirdSpan)

    then:
    tracer.activeSpan() == thirdSpan
    tracer.activeScope() == thirdScope

    when:
    secondScope.close()

    then:
    tracer.activeSpan() == thirdSpan
    tracer.activeScope() == thirdScope

    when:
    thirdScope.close()

    then:
    tracer.activeSpan() == firstSpan
    tracer.activeScope() == firstScope

    when:
    firstScope.close()

    then:
    tracer.activeScope() == null
  }

  boolean spanFinished(AgentSpan span) {
    return ((DDSpan) span)?.isFinished()
  }
}

class EventCountingListener implements ScopeListener {
  AtomicInteger activatedCount = new AtomicInteger(0)
  AtomicInteger closedCount = new AtomicInteger(0)

  void afterScopeActivated() {
    activatedCount.incrementAndGet()
  }

  @Override
  void afterScopeClosed() {
    closedCount.incrementAndGet()
  }
}
