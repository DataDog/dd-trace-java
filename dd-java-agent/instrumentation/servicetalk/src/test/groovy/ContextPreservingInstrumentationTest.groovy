import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.servicetalk.concurrent.api.AsyncContext
import io.servicetalk.context.api.ContextMap

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ContextPreservingInstrumentationTest extends AgentTestRunner {

  def "wrapBiConsumer"() {
    setup:
    def parent = startParentContext()
    def wrapped =
      asyncContextProvider.wrapBiConsumer({ t, u -> childSpan() }, parent.contextMap)

    when:
    runInSeparateThread{ wrapped.accept(null, null) }
    parent.releaseParentSpan()

    then:
    assertParentChildTrace()
  }

  def "wrapBiFunction"() {
    setup:
    def parent = startParentContext()
    def wrapped =
      asyncContextProvider.wrapBiFunction({ t, u -> childSpan() }, parent.contextMap)

    when:
    runInSeparateThread{ wrapped.apply(null, null) }
    parent.releaseParentSpan()

    then:
    assertParentChildTrace()
  }

  def "wrapCallable"() {
    setup:
    def parent = startParentContext()
    def wrapped =
      asyncContextProvider.wrapCallable({ -> childSpan() }, parent.contextMap)

    when:
    runInSeparateThread{ wrapped.call() }
    parent.releaseParentSpan()

    then:
    assertParentChildTrace()
  }

  def "wrapConsumer"() {
    setup:
    def parent = startParentContext()
    def wrapped =
      asyncContextProvider.wrapConsumer({ t -> childSpan() }, parent.contextMap)

    when:
    runInSeparateThread{ wrapped.accept(null) }
    parent.releaseParentSpan()

    then:
    assertParentChildTrace()
  }

  def "wrapFunction"() {
    setup:
    def parent = startParentContext()
    def wrapped =
      asyncContextProvider.wrapFunction({ t -> childSpan() }, parent.contextMap)

    when:
    runInSeparateThread { wrapped.apply(null) }
    parent.releaseParentSpan()

    then:
    assertParentChildTrace()
  }

  def "wrapRunnable"() {
    setup:
    def parent = startParentContext()
    def wrapped =
      asyncContextProvider.wrapRunnable({ -> childSpan() }, parent.contextMap)

    when:
    runInSeparateThread(wrapped)
    parent.releaseParentSpan()

    then:
    assertParentChildTrace()
  }

  ExecutorService executor = Executors.newFixedThreadPool(5)
  def asyncContextProvider = AsyncContext.provider

  def cleanup() {
    if (executor != null) {
      executor.shutdown()
    }
  }

  private runInSeparateThread(Runnable runnable) {
    executor.submit(runnable).get()
  }

  /**
   * Captures async context. Also uses continuation to prevent the span from being reported until it is released.
   */
  private class ParentContext {
    final ContextMap contextMap = AsyncContext.context().copy()
    final AgentScope.Continuation spanContinuation = AgentTracer.capture()

    def releaseParentSpan() {
      spanContinuation.cancel()
    }
  }

  private startParentContext() {
    runUnderTrace("parent") {
      new ParentContext()
    }
  }

  /**
   * Asserts a parent-child trace meaning that async context propagation works correctly.
   */
  private void assertParentChildTrace() {
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        span {
          operationName "parent"
          tags {
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName "child"
          tags {
            defaultTags()
          }
        }
      }
    }
  }

  private childSpan() {
    AgentTracer.startSpan("test", "child").finish()
  }
}
