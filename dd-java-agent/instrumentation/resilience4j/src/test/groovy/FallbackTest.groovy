import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.decorators.Decorators
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class FallbackTest extends AgentTestRunner {

  def "ofCompletionStage"() {
    setup:
    def executor = Executors.newSingleThreadExecutor()
    when:
    Supplier<CompletionStage<String>> supplier = Decorators
      .ofCompletionStage {
        CompletableFuture.supplyAsync({
          serviceCallErr(new IllegalStateException("test"))
        }, executor)
      }
      .withFallback({ Throwable t ->
        serviceCall("fallback", "fallback")
      } as Function<Throwable, String>)
      .decorate()
    def future = runUnderTrace("parent") { supplier.get().toCompletableFuture() }
    future.get()

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          parent()
          errored false
        }
        // TODO do we need a fallback span?
        span(1) {
          operationName "serviceCall"
          childOf span(0)
          errored false
        }
        span(2) {
          operationName "fallback"
          childOf span(0)
          errored false
        }
      }
    }
  }

  def "ofSupplier"() {
    when:
    Supplier<String> supplier = Decorators
      .ofSupplier {
        serviceCallErr(new IllegalStateException("test"))
      }
      .withFallback({ Throwable t ->
        serviceCall("fallbackResult", "fallbackCall")
      } as Function<Throwable, String>)
      .decorate()

    def result = runUnderTrace("parent") { supplier.get() }

    then:
    result == "fallbackResult"
    assertTraces(1) {
      trace(4) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          parent()
          errored false
        }
        span(1) {
          operationName "resilience4j.fallback"
          childOf span(0)
          errored false
        }
        span(2) {
          operationName "serviceCall"
          childOf span(1)
          errored false
        }
        span(3) {
          operationName "fallbackCall"
          childOf span(1)
          errored false
        }
      }
    }
  }

  def <T> T serviceCall(T value, String name) {
    AgentTracer.startSpan("test", name).finish()
    value
  }

  <T> T serviceCallErr(IllegalStateException e) {
    def span = AgentTracer.startSpan("test", "serviceCall")
    try (def ignored = AgentTracer.activateSpan(span)) {
      throw e
    } finally {
      span.finish()
    }
  }
}
