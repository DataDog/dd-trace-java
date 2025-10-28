import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.servicetalk.concurrent.api.AsyncContext
import io.servicetalk.concurrent.api.CapturedContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ContextPreservingInstrumentationTest extends InstrumentationSpecification {

  def "capturedContext"() {
    setup:
    def parent = startParentContext()

    when:
    runInSeparateThread {
      parent.capturedContext.attachContext()
      try (def _ = parent.capturedContext.attachContext()) {
        childSpan()
      }
    }
    parent.releaseParentSpan()

    then:
    assertParentChildTrace()
  }

  def "capturedContext without an active span"() {
    when:
    runInSeparateThread {
      try (def _ = asyncContextProvider.captureContext().attachContext()) {
        childSpan()
      }
    }

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "child"
          tags {
            defaultTags()
          }
        }
      }
    }
  }

  def "wrapBiConsumer"() {
    setup:
    def parent = startParentContext()
    def wrapped =
    asyncContextProvider.wrapBiConsumer({ t, u -> childSpan() }, parent.capturedContext)

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
    asyncContextProvider.wrapBiFunction({ t, u -> childSpan() }, parent.capturedContext)

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
    asyncContextProvider.wrapCallable({ -> childSpan() }, parent.capturedContext)

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
    asyncContextProvider.wrapConsumer({ t -> childSpan() }, parent.capturedContext)

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
    asyncContextProvider.wrapFunction({ t -> childSpan() }, parent.capturedContext)

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
    asyncContextProvider.wrapRunnable({ -> childSpan() },  parent.capturedContext)

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
    final CapturedContext capturedContext = asyncContextProvider.captureContext()
    final AgentScope.Continuation spanContinuation = AgentTracer.captureActiveSpan()

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

  private static childSpan() {
    AgentTracer.startSpan("test", "child").finish()
  }
}
