package datadog.trace.core.scopemanager

import datadog.trace.agent.test.utils.ThreadUtils
import datadog.trace.api.Checkpointer
import datadog.trace.api.DDId
import datadog.trace.api.StatsDClient
import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.api.interceptor.TraceInterceptor
import datadog.trace.api.scopemanager.ExtendedScopeListener
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentSpan
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.ScopeListener
import datadog.trace.context.TraceScope
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Shared

import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.core.scopemanager.EVENT.ACTIVATE
import static datadog.trace.core.scopemanager.EVENT.CLOSE
import static datadog.trace.test.util.GCUtils.awaitGC

enum EVENT {
  ACTIVATE, CLOSE
}

class ScopeManagerTest extends DDCoreSpecification {
  private static final long TIMEOUT_MS = 10_000

  @Override
  protected boolean useStrictTraceWrites() {
    // This tests the behavior of the relaxed pending trace implementation
    return false
  }

  ListWriter writer
  CoreTracer tracer
  ContinuableScopeManager scopeManager
  StatsDClient statsDClient
  EventCountingListener eventCountingListener
  EventCountingExtendedListener eventCountingExtendedListener
  Checkpointer checkpointer

  def setup() {
    checkpointer = Mock()
    writer = new ListWriter()
    statsDClient = Mock()
    tracer = tracerBuilder().writer(writer).statsDClient(statsDClient).build()
    tracer.registerCheckpointer(checkpointer)
    scopeManager = tracer.scopeManager
    eventCountingListener = new EventCountingListener()
    scopeManager.addScopeListener(eventCountingListener)
    eventCountingExtendedListener = new EventCountingExtendedListener()
    scopeManager.addScopeListener(eventCountingExtendedListener)
  }

  def cleanup() {
    tracer.close()
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

  def "no scope is active before activation"() {
    setup:
    def builder = tracer.buildSpan("test")
    builder.start()

    expect:
    scopeManager.active() == null
    writer.empty
  }

  def "simple scope and span lifecycle"() {
    when:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)

    then:
    scope.span() == span
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
    when:
    def parentSpan = tracer.buildSpan("parent").start()
    def parentScope = tracer.activateSpan(parentSpan)
    def childSpan = tracer.buildSpan("child").start()
    def childScope = tracer.activateSpan(childSpan)

    then:
    scopeManager.active() == childScope
    childScope.span().context().parentId == parentScope.span().context().spanId
    childScope.span().context().trace == parentScope.span().context().trace

    when:
    childScope.close()

    then:
    scopeManager.active() == parentScope
    !spanFinished(childScope.span())
    !spanFinished(parentScope.span())
    writer == []
  }

  def "sets parent as current upon close with noop child"() {
    when:
    def parentSpan = tracer.buildSpan("parent").start()
    def parentScope = tracer.activateSpan(parentSpan)
    def childSpan = NoopAgentSpan.INSTANCE
    def childScope = tracer.activateSpan(childSpan)

    then:
    scopeManager.active() == childScope

    when:
    childScope.close()

    then:
    scopeManager.active() == parentScope
    !spanFinished(parentScope.span())
    writer == []
  }

  def "DDScope only creates continuations when propagation is set"() {
    when:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)
    scope.setAsyncPropagation(false)
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()

    then:
    continuation == null

    when:
    scope.setAsyncPropagation(true)
    continuation = concurrent ? scope.captureConcurrent() : scope.capture()

    then:
    continuation != null

    cleanup:
    continuation.cancel()

    where:
    concurrent << [false, true]
  }

  def "Continuation.cancel doesn't close parent scope"() {
    when:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()

    then:
    continuation != null

    when:
    continuation.cancel()

    then:
    scopeManager.active() == scope

    where:
    concurrent << [false, true]
  }

  def "test continuation doesn't have hard reference on scope"() {
    when:
    def span = tracer.buildSpan("test").start()
    def scopeRef = new AtomicReference<AgentScope>(tracer.activateSpan(span))
    scopeRef.get().setAsyncPropagation(true)
    def continuation = concurrent ? scopeRef.get().captureConcurrent() : scopeRef.get().capture()

    then:
    continuation != null

    when:
    scopeRef.get().close()

    then:
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

  def "hard reference on continuation does not prevent trace from reporting"() {
    when:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()

    then:
    continuation != null

    when:
    scope.close()
    span.finish()
    if (autoClose) {
      continuation.cancel()
    }

    then:
    scopeManager.active() == null
    spanFinished(span)

    when:
    writer.waitForTraces(1)

    then:
    writer == [[span]]

    where:
    autoClose | concurrent
    true      | true
    true      | false
    false     | true
    false     | false
  }

  def "continuation restores trace"() {
    when:
    def parentSpan = tracer.buildSpan("parent").start()
    def parentScope = tracer.activateSpan(parentSpan)
    def childSpan = tracer.buildSpan("child").start()
    def childScope = tracer.activateSpan(childSpan)
    childScope.setAsyncPropagation(true)

    def continuation = concurrentChild ? childScope.captureConcurrent() : childScope.capture()
    childScope.close()

    then:
    continuation != null
    scopeManager.active() == parentScope
    !spanFinished(childSpan)
    !spanFinished(parentSpan)

    when:
    parentScope.close()
    parentSpan.finish()

    then: "parent span is finished, but trace is not reported"
    scopeManager.active() == null
    !spanFinished(childSpan)
    spanFinished(parentSpan)
    writer == []

    when: "activating the continuation"
    def newScope = continuation.activate()
    if (concurrentChild) {
      continuation.cancel()
    }

    then: "the continued scope becomes active and span state doesnt change"
    newScope instanceof ContinuableScopeManager.ContinuableScope
    newScope.isAsyncPropagating()
    scopeManager.active() == newScope
    newScope != childScope
    newScope != parentScope
    newScope.span() == childSpan
    !spanFinished(childSpan)
    spanFinished(parentSpan)
    writer == []

    when: "creating and activating a second continuation"
    def newContinuation = concurrentNew ? newScope.captureConcurrent() : newScope.capture()
    newScope.close()
    def secondContinuedScope = newContinuation.activate()
    secondContinuedScope.close()
    if (concurrentNew) {
      newContinuation.cancel()
    }
    childSpan.finish()
    writer.waitForTraces(1)

    then: "spans are all finished and trace is reported"
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
    when: "creating and activating a continuation"
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()
    scope.close()
    span.finish()

    def newScope = continuation.activate()
    if (concurrent) {
      continuation.cancel()
    }

    then: "the continuation sets the active scope"
    newScope instanceof ContinuableScopeManager.ContinuableScope
    newScope != scope
    scopeManager.active() == newScope
    spanFinished(span)
    writer == []

    when: "creating a new child span under a continued scope"
    def childSpan = tracer.buildSpan("child").start()
    def childScope = tracer.activateSpan(childSpan)
    childScope.close()
    childSpan.finish()

    then:
    scopeManager.active() == newScope

    when:
    scopeManager.active().close()
    writer.waitForTraces(1)

    then: "the child has the correct parent"
    scopeManager.active() == null
    spanFinished(childSpan)
    childSpan.context().parentId == span.context().spanId
    writer == [[childSpan, span]]

    where:
    concurrent << [false, true]
  }

  def "test activating same span multiple times"() {
    setup:
    def span = tracer.buildSpan("test").start()

    when:
    AgentScope scope1 = scopeManager.activate(span, ScopeSource.INSTRUMENTATION)

    then:
    assertEvents([ACTIVATE])

    when:
    AgentScope scope2 = scopeManager.activate(span, ScopeSource.INSTRUMENTATION)

    then: 'Activating the same span multiple times does not create a new scope'
    assertEvents([ACTIVATE])

    when:
    scope2.close()

    then: 'Closing a scope once that has been activated multiple times does not close'
    assertEvents([ACTIVATE])

    when:
    scope1.close()

    then:
    assertEvents([ACTIVATE, CLOSE])
  }

  def "opening and closing multiple scopes"() {
    when:
    AgentSpan span = tracer.buildSpan("foo").start()
    AgentScope continuableScope = tracer.activateSpan(span)

    then:
    continuableScope instanceof ContinuableScopeManager.ContinuableScope
    assertEvents([ACTIVATE])

    when:
    AgentSpan childSpan = tracer.buildSpan("foo").start()
    AgentScope childDDScope = tracer.activateSpan(childSpan)

    then:
    childDDScope instanceof ContinuableScopeManager.ContinuableScope
    assertEvents([ACTIVATE, ACTIVATE])

    when:
    childDDScope.close()
    childSpan.finish()

    then:
    assertEvents([ACTIVATE, ACTIVATE, CLOSE, ACTIVATE])

    when:
    continuableScope.close()
    span.finish()

    then:
    assertEvents([ACTIVATE, ACTIVATE, CLOSE, ACTIVATE, CLOSE])
  }

  def "closing scope out of order - simple"() {
    when:
    AgentSpan firstSpan = tracer.buildSpan("foo").start()
    AgentScope firstScope = tracer.activateSpan(firstSpan)

    AgentSpan secondSpan = tracer.buildSpan("bar").start()
    AgentScope secondScope = tracer.activateSpan(secondSpan)

    firstSpan.finish()
    firstScope.close()

    then:
    assertEvents([ACTIVATE, ACTIVATE])
    2 * checkpointer.checkpoint(_, SPAN) // two spans started by test
    1 * checkpointer.checkpoint(_, SPAN | END) // span ended by test
    1 * statsDClient.incrementCounter("scope.close.error")
    0 * _

    when:
    secondSpan.finish()
    secondScope.close()

    then:
    1 * checkpointer.checkpoint(_, SPAN | END) // span ended by test
    1 * checkpointer.onRootSpan(_, _)
    assertEvents([ACTIVATE, ACTIVATE, CLOSE, CLOSE])
    0 * _

    when:
    firstScope.close()

    then:
    assertEvents([ACTIVATE, ACTIVATE, CLOSE, CLOSE])
    1 * statsDClient.incrementCounter("scope.close.error")
  }

  def "closing scope out of order - complex"() {
    // Events are checked twice in each case to ensure a call to
    // tracer.activeScope() or tracer.activeSpan() doesn't change the count

    when:
    AgentSpan firstSpan = tracer.buildSpan("foo").start()
    AgentScope firstScope = tracer.activateSpan(firstSpan)

    then:
    assertEvents([ACTIVATE])

    tracer.activeSpan() == firstSpan
    tracer.activeScope() == firstScope
    assertEvents([ACTIVATE])
    1 * checkpointer.checkpoint(_, SPAN) // span started by test
    0 * _

    when:
    AgentSpan secondSpan = tracer.buildSpan("bar").start()
    AgentScope secondScope = tracer.activateSpan(secondSpan)

    then:
    1 * checkpointer.checkpoint(_, SPAN) // span started by test
    assertEvents([ACTIVATE, ACTIVATE])
    tracer.activeSpan() == secondSpan
    tracer.activeScope() == secondScope
    assertEvents([ACTIVATE, ACTIVATE])
    0 * _

    when:
    AgentSpan thirdSpan = tracer.buildSpan("quux").start()
    AgentScope thirdScope = tracer.activateSpan(thirdSpan)

    then:
    1 * checkpointer.checkpoint(_, SPAN) // span started by test
    assertEvents([ACTIVATE, ACTIVATE, ACTIVATE])
    tracer.activeSpan() == thirdSpan
    tracer.activeScope() == thirdScope
    assertEvents([ACTIVATE, ACTIVATE, ACTIVATE])
    0 * _

    when:
    secondScope.close()

    then:
    assertEvents([ACTIVATE, ACTIVATE, ACTIVATE])
    tracer.activeSpan() == thirdSpan
    tracer.activeScope() == thirdScope
    assertEvents([ACTIVATE, ACTIVATE, ACTIVATE])
    1 * statsDClient.incrementCounter("scope.close.error")
    0 * _

    when:
    thirdScope.close()

    then:
    assertEvents([ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE])
    tracer.activeSpan() == firstSpan
    tracer.activeScope() == firstScope

    assertEvents([ACTIVATE, ACTIVATE, ACTIVATE, CLOSE, CLOSE, ACTIVATE])
    0 * _

    when:
    firstScope.close()

    then:
    assertEvents([
      ACTIVATE,
      ACTIVATE,
      ACTIVATE,
      CLOSE,
      CLOSE,
      ACTIVATE,
      CLOSE
    ])
    tracer.activeScope() == null
    assertEvents([
      ACTIVATE,
      ACTIVATE,
      ACTIVATE,
      CLOSE,
      CLOSE,
      ACTIVATE,
      CLOSE
    ])
    0 * _
  }

  def "closing scope out of order - multiple activations"() {
    setup:
    def span = tracer.buildSpan("test").start()

    when:
    AgentScope scope1 = scopeManager.activate(span, ScopeSource.INSTRUMENTATION)

    then:
    assertEvents([ACTIVATE])

    when:
    AgentScope scope2 = scopeManager.activate(span, ScopeSource.INSTRUMENTATION)

    then: 'Activating the same span multiple times does not create a new scope'
    assertEvents([ACTIVATE])

    when:
    AgentSpan thirdSpan = tracer.buildSpan("quux").start()
    AgentScope thirdScope = tracer.activateSpan(thirdSpan)
    0 * _

    then:
    1 * checkpointer.checkpoint(_, SPAN) // span started by test
    assertEvents([ACTIVATE, ACTIVATE])
    tracer.activeSpan() == thirdSpan
    tracer.activeScope() == thirdScope
    assertEvents([ACTIVATE, ACTIVATE])
    0 * _

    when:
    scope2.close()

    then: 'Closing a scope once that has been activated multiple times does not close'
    assertEvents([ACTIVATE, ACTIVATE])
    1 * statsDClient.incrementCounter("scope.close.error")
    0 * _

    when:
    thirdScope.close()
    thirdSpan.finish()

    then: 'Closing scope above multiple activated scope does not close it'
    assertEvents([ACTIVATE, ACTIVATE, CLOSE, ACTIVATE])
    1 * checkpointer.checkpoint(_, SPAN | END) // span finished by test
    0 * _

    when:
    scope1.close()

    then:
    assertEvents([ACTIVATE, ACTIVATE, CLOSE, ACTIVATE, CLOSE])
  }

  def "Closing a continued scope out of order cancels the continuation"() {
    when:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)
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
    if (concurrent) {
      continuation.cancel()
    }
    AgentSpan secondSpan = tracer.buildSpan("test2").start()
    AgentScope secondScope = (ContinuableScopeManager.ContinuableScope) tracer.activateSpan(secondSpan)

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

  def "exception thrown in TraceInterceptor does not leave scope manager in bad state "() {
    setup:
    def interceptor = new ExceptionThrowingInterceptor()
    tracer.addTraceInterceptor(interceptor)

    when:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)
    scope.close()
    span.finish()

    then: "exception is thrown in same thread"
    interceptor.lastTrace == [span]

    and: "scopeManager in good state"
    scopeManager.active() == null
    spanFinished(span)
    scopeManager.scopeStack().depth() == 0
    writer == []

    when: "completing another scope lifecycle"
    def span2 = tracer.buildSpan("test").start()
    def scope2 = tracer.activateSpan(span2)

    then:
    scopeManager.active() == scope2

    when:
    interceptor.shouldThrowException = false
    scope2.close()
    span2.finish()
    writer.waitForTraces(1)

    then: "second lifecycle gets reported"
    scopeManager.active() == null
    spanFinished(span2)
    scopeManager.scopeStack().depth() == 0
    writer == [[span2]]
  }

  def "exception thrown in TraceInterceptor does not leave scope manager in bad state when reporting through PendingTraceBuffer"() {
    setup:
    def interceptor = new ExceptionThrowingInterceptor()
    tracer.addTraceInterceptor(interceptor)

    when:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = concurrent ? scope.captureConcurrent() : scope.capture()
    scope.close()
    span.finish()

    then:
    continuation != null
    scopeManager.active() == null
    spanFinished(span)
    scopeManager.scopeStack().depth() == 0
    writer == []

    when: "wait for root span to be reported from PendingTraceBuffer"
    // can't use "waitForTraces" because the trace never gets reported
    def deadline = System.currentTimeMillis() + TIMEOUT_MS
    while (System.currentTimeMillis() < deadline && interceptor.lastTrace == null) {
      Thread.sleep(200)
    }

    then:
    interceptor.lastTrace == [span]

    and: "scopeManager in good state"
    scopeManager.active() == null
    spanFinished(span)
    scopeManager.scopeStack().depth() == 0
    writer == []

    when: "completing another async scope lifecycle"
    def span2 = tracer.buildSpan("test").start()
    def scope2 = tracer.activateSpan(span2)
    scope2.setAsyncPropagation(true)
    def continuation2 = concurrent ? scope2.captureConcurrent() : scope2.capture()

    then:
    continuation2 != null
    scopeManager.active() == scope2

    when:
    interceptor.shouldThrowException = false
    scope2.close()
    span2.finish()

    // The second trace also goes through PendingTraceBuffer, but since we're not throwing an exception
    // we can use the normal "waitForTraces"
    writer.waitForTraces(1)

    then: "second lifecycle gets reported as well"
    scopeManager.active() == null
    spanFinished(span2)
    scopeManager.scopeStack().depth() == 0
    writer == [[span2]]

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

  def "scope listener should be notified about the currently active scope"() {
    setup:
    def span = tracer.buildSpan("test").start()

    when:
    AgentScope scope = scopeManager.activate(span, ScopeSource.INSTRUMENTATION)

    then:
    assertEvents([ACTIVATE])

    when:
    def listener = new EventCountingListener()

    then:
    listener.events == []

    when:
    scopeManager.addScopeListener(listener)

    then:
    listener.events == [ACTIVATE]

    when:
    scope.close()

    then:
    assertEvents([ACTIVATE, CLOSE])
    listener.events == [ACTIVATE, CLOSE]
  }

  def "extended scope listener should be notified about the currently active scope"() {
    setup:
    def span = tracer.buildSpan("test").start()

    when:
    AgentScope scope = scopeManager.activate(span, ScopeSource.INSTRUMENTATION)

    then:
    assertEvents([ACTIVATE])

    when:
    def listener = new EventCountingExtendedListener()

    then:
    listener.events == []

    when:
    scopeManager.addScopeListener(listener)

    then:
    listener.events == [ACTIVATE]

    when:
    scope.close()

    then:
    assertEvents([ACTIVATE, CLOSE])
    listener.events == [ACTIVATE, CLOSE]
  }

  def "scope listener should not be notified when there is no active scope"() {
    when:
    def listener = new EventCountingListener()

    then:
    listener.events == []

    when:
    scopeManager.addScopeListener(listener)

    then:
    listener.events == []
  }

  def "misbehaving ScopeListener should not affect others"() {
    setup:
    def exceptionThrowingScopeLister = new ExceptionThrowingScopeListener()
    exceptionThrowingScopeLister.throwOnScopeActivated = activationException
    exceptionThrowingScopeLister.throwOnScopeClosed = closeException

    def secondEventCountingListener = new EventCountingListener()
    scopeManager.addScopeListener(exceptionThrowingScopeLister)
    scopeManager.addScopeListener(secondEventCountingListener)

    when:
    AgentSpan span = tracer.buildSpan("foo").start()
    AgentScope continuableScope = tracer.activateSpan(span)

    then:
    assertEvents([ACTIVATE])
    secondEventCountingListener.events == [ACTIVATE]

    when:
    AgentSpan childSpan = tracer.buildSpan("foo").start()
    AgentScope childDDScope = tracer.activateSpan(childSpan)

    then:
    assertEvents([ACTIVATE, ACTIVATE])
    secondEventCountingListener.events == [ACTIVATE, ACTIVATE]

    when:
    childDDScope.close()
    childSpan.finish()

    then:
    assertEvents([ACTIVATE, ACTIVATE, CLOSE, ACTIVATE])
    secondEventCountingListener.events == [ACTIVATE, ACTIVATE, CLOSE, ACTIVATE]

    when:
    continuableScope.close()
    span.finish()

    then:
    assertEvents([ACTIVATE, ACTIVATE, CLOSE, ACTIVATE, CLOSE])
    secondEventCountingListener.events == [ACTIVATE, ACTIVATE, CLOSE, ACTIVATE, CLOSE]

    where:
    activationException | closeException
    false               | false
    false               | true
    true                | false
    true                | true
  }

  boolean spanFinished(AgentSpan span) {
    return ((DDSpan) span)?.isFinished()
  }

  def assertEvents(List<EVENT> events) {
    assert eventCountingListener.events == events
    assert eventCountingExtendedListener.events == events
    return true
  }
}

class EventCountingListener implements ScopeListener {
  public final List<EVENT> events = new ArrayList<>()

  @Override
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

class EventCountingExtendedListener implements ExtendedScopeListener {
  public final List<EVENT> events = new ArrayList<>()

  @Override
  void afterScopeActivated() {
    throw new IllegalArgumentException("This should not be called")
  }

  @Override
  void afterScopeActivated(DDId traceId, DDId spanId) {
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

class ExceptionThrowingScopeListener implements ScopeListener {
  boolean throwOnScopeActivated = false
  boolean throwOnScopeClosed = false

  @Override
  void afterScopeActivated() {
    if (throwOnScopeActivated) {
      throw new RuntimeException("Exception on activated")
    }
  }

  @Override
  void afterScopeClosed() {
    if (throwOnScopeClosed) {
      throw new RuntimeException("Exception on closed")
    }
  }
}

class ExceptionThrowingInterceptor implements TraceInterceptor {
  def shouldThrowException = true

  Collection<? extends MutableSpan> lastTrace

  @Override
  Collection<? extends MutableSpan> onTraceComplete(Collection<? extends MutableSpan> trace) {
    lastTrace = trace
    if (shouldThrowException) {
      throw new RuntimeException("Always throws exception")
    } else {
      return trace
    }
  }

  @Override
  int priority() {
    return 55
  }
}
