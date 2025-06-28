import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.cache.Cache
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.core.functions.CheckedFunction
import io.github.resilience4j.core.functions.CheckedSupplier
import io.github.resilience4j.decorators.Decorators
import io.github.resilience4j.retry.Retry

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class StackedDecoratorsTest extends AgentTestRunner {

  //TODO ideally we should test all possible combination of decorators in different orders and different kind to make sure the ContextHolder is constructed only once per stack
  // If some intermediate instrumentation is missing it will result in two separate ContextHolder instances and as a result two separate spans.

  def "happy path sync test"() {
    when:
    Supplier<String> supplier = Decorators
      .ofSupplier{serviceCall("foobar", "serviceCall")}
      .withCircuitBreaker(CircuitBreaker.ofDefaults("A"))
      //      .withRateLimiter(RateLimiter.ofDefaults("L"))
      .withRetry(Retry.ofDefaults("R"))
      //      .withBulkhead(Bulkhead.ofDefaults("B"))
      .withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as Function<Throwable, String>)
      //      .withCache(Cache.of(Caching.getCache("cacheName", String.class, String.class)))
      .decorate()

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "happy path async test"() {
    when:
    Supplier<CompletionStage<String>> supplier = Decorators
      .ofCompletionStage {
        CompletableFuture.supplyAsync({
          serviceCall("foobar", "serviceCall")
        }, Executors.newSingleThreadExecutor())
      }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("A"))
      .withRetry(Retry.ofDefaults("R"), Executors.newSingleThreadScheduledExecutor())
      .withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as Function<Throwable, String>)
      .decorate()

    then:
    runUnderTrace("parent"){supplier.get().toCompletableFuture()}.get() == "foobar"
    and:
    assertExpectedTrace()
  }

  def "generate separate resilience4j span for each decorator [CompletionStage]"() {
    //TODO
    when:
    Supplier<CompletionStage<String>> inner = Decorators
      .ofCompletionStage {
        CompletableFuture.supplyAsync({
          serviceCall("foobar", "serviceCall")
        }, Executors.newSingleThreadExecutor())
      }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("A"))
      .withRetry(Retry.ofDefaults("R"), Executors.newSingleThreadScheduledExecutor())
      //      .withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as CheckedFunction<Throwable, String>) // TODO
      .decorate()

    Supplier<CompletionStage<String>> outer = Decorators
      .ofCompletionStage {
        inner.get()
      }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("A"))
      .withRetry(Retry.ofDefaults("R"), Executors.newSingleThreadScheduledExecutor())
      //      .withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as CheckedFunction<Throwable, String>) // TODO
      .decorate()

    then:
    runUnderTrace("parent") { outer.get().toCompletableFuture().get() } == "foobar"

    and:
    assertSeparateDecoratorSpans()
  }

  def "generate separate resilience4j span for each decorator"() {
    when:
    CheckedSupplier<String> inner = Decorators
      .ofCheckedSupplier { serviceCall("foobar", "serviceCall") }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("A"))
      .withRetry(Retry.ofDefaults("R")) // TODO
      .withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as CheckedFunction<Throwable, String>) // TODO
      .decorate()

    CheckedSupplier<String> outer = Decorators
      .ofCheckedSupplier { inner.get() }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("A"))
      .withRetry(Retry.ofDefaults("R"))
      .withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as CheckedFunction<Throwable, String>)
      .decorate()

    then:
    runUnderTrace("parent") { outer.get() } == "foobar"

    and:
    assertSeparateDecoratorSpans()
  }

  def "generate separate resilience4j span for each decorator"() {
    when:
    Supplier<String> inner = Decorators
      .ofSupplier { serviceCall("foobar", "serviceCall") }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("A"))
      .withRetry(Retry.ofDefaults("R")) // TODO
      .withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as Function<Throwable, String>) // TODO
      .decorate()

    Supplier<String> outer = Decorators
      .ofSupplier { inner.get() }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("A"))
      .withRetry(Retry.ofDefaults("R"))
      .withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as Function<Throwable, String>)
      .decorate()

    then:
    runUnderTrace("parent") { outer.get() } == "foobar"

    and:
    assertSeparateDecoratorSpans()
  }

  private void assertExpectedTrace() {
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          parent()
          errored false
        }
        span(1) {
          operationName "resilience4j"
          childOf span(0)
          errored false
        }
        span(2) {
          operationName "serviceCall"
          childOf span(1)
          errored false
        }
      }
    }
  }

  private void assertSeparateDecoratorSpans() {
    assertTraces(1) {
      trace(4) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          parent()
          errored false
        }
        span(1) {
          operationName "resilience4j" // outer
          childOf span(0)
          errored false
        }
        span(2) {
          operationName "resilience4j" // inner
          childOf span(1)
          errored false
        }
        span(3) {
          operationName "serviceCall"
          childOf span(2)
          errored false
        }
      }
    }
  }

  def <T> T serviceCall(T value, String name) {
    AgentTracer.startSpan("test", name).finish()
    value
  }
}
