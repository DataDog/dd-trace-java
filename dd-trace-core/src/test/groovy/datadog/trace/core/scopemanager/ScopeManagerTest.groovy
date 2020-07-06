package datadog.trace.core.scopemanager

import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.ScopeListener
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.util.test.DDSpecification
import spock.lang.Shared
import spock.lang.Subject
import spock.lang.Timeout

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.util.gc.GCUtils.awaitGC
import static java.util.concurrent.TimeUnit.SECONDS

class ScopeManagerTest extends DDSpecification {

  @Shared
  CountDownLatch latch
  @Shared
  ListWriter writer
  @Shared
  CoreTracer tracer

  @Shared
  @Subject
  ContinuableScopeManager scopeManager

  def setupSpec() {
    latch = new CountDownLatch(1)
    final currentLatch = latch
    writer = new ListWriter() {
      void incrementTraceCount() {
        currentLatch.countDown()
      }
    }
    tracer = CoreTracer.builder().writer(writer).build()
    scopeManager = tracer.scopeManager
  }

  def cleanup() {
    scopeManager.tlsScope.remove()
    scopeManager.scopeListeners.clear()
    writer.clear()
  }

  def "non-ddspan activation results in a continuable scope"() {
    when:
    def scope = scopeManager.activate(NoopAgentSpan.INSTANCE)

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
    scopeManager.tlsScope.get() == scope

    when:
    def cont = scope.capture()
    scope.close()

    then:
    scopeManager.tlsScope.get() == null

    when:
    def newScope = cont.activate()

    then:
    newScope != scope
    scopeManager.tlsScope.get() == newScope
  }

  def "add scope listener"() {
    setup:
    AtomicInteger activatedCount = new AtomicInteger(0)
    AtomicInteger closedCount = new AtomicInteger(0)

    scopeManager.addScopeListener(new ScopeListener() {
      @Override
      void afterScopeActivated() {
        activatedCount.incrementAndGet()
      }

      @Override
      void afterScopeClosed() {
        closedCount.incrementAndGet()
      }
    })

    when:
    AgentScope scope1 = scopeManager.activate(NoopAgentSpan.INSTANCE)

    then:
    activatedCount.get() == 1
    closedCount.get() == 0

    when:
    AgentScope scope2 = scopeManager.activate(NoopAgentSpan.INSTANCE)

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

    when:
    AgentSpan span = tracer.buildSpan("foo").start()
    AgentScope continuableScope = tracer.activateSpan(span)

    then:
    continuableScope instanceof ScopeInterceptor.Scope
    activatedCount.get() == 2
    closedCount.get() == 1

    when:
    AgentSpan childSpan = tracer.buildSpan("foo").start()
    AgentScope childDDScope = tracer.activateSpan(childSpan)

    then:
    childDDScope instanceof ScopeInterceptor.Scope
    activatedCount.get() == 3
    closedCount.get() == 1

    when:
    childDDScope.close()
    childSpan.finish()

    then:
    activatedCount.get() == 3
    closedCount.get() == 2

    when:
    continuableScope.close()
    span.finish()

    then:
    activatedCount.get() == 3 // the last scope closed was the last one on the stack so nothing more got activated
    closedCount.get() == 3
  }

  boolean spanFinished(AgentSpan span) {
    return ((DDSpan) span)?.isFinished()
  }
}
