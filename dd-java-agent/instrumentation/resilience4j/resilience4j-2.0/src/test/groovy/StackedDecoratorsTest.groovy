import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.decorators.Decorators
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.retry.Retry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.function.Function
import java.util.function.Supplier
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class StackedDecoratorsTest extends InstrumentationSpecification {

  def "happy path sync test"() {
    when:
    Supplier<String> supplier = Decorators
      .ofSupplier{serviceCall("foobar", "serviceCall")}
      .withCircuitBreaker(CircuitBreaker.ofDefaults("A"))
      .withRateLimiter(RateLimiter.ofDefaults("L")) // not instrumented, doesn't break the scope
      .withRetry(Retry.ofDefaults("R"))
      .withBulkhead(Bulkhead.ofDefaults("B")) // not instrumented, doesn't break the scope
      .withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as Function<Throwable, String>)
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

  def <T> T serviceCall(T value, String name) {
    AgentTracer.startSpan("test", name).finish()
    value
  }
}
