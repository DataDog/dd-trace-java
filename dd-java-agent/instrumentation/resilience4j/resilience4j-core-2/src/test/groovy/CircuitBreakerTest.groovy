import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.core.functions.CheckedConsumer
import io.github.resilience4j.core.functions.CheckedRunnable
import io.github.resilience4j.core.functions.CheckedSupplier
import io.github.resilience4j.core.functions.CheckedFunction
import io.github.resilience4j.decorators.Decorators

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class CircuitBreakerTest extends AgentTestRunner {

  // TODO test all io.github.resilience4j.decorators.Decorators.of* decorators

  def "decorate span with circuit-breaker"() {
    def ms = Mock(CircuitBreaker.Metrics)

    def cb = Mock(CircuitBreaker)
    cb.getName() >> "cb1"
    cb.getState() >> CircuitBreaker.State.CLOSED
    cb.tryAcquirePermission() >> true
    cb.getMetrics() >> ms
    ms.getFailureRate() >> 0.1f
    ms.getSlowCallRate() >> 0.2f
    ms.getNumberOfBufferedCalls() >> 12
    ms.getNumberOfFailedCalls() >> 13
    ms.getNumberOfNotPermittedCalls() >> 2
    ms.getNumberOfSlowCalls() >> 23
    ms.getNumberOfSlowFailedCalls() >> 3
    ms.getNumberOfSlowSuccessfulCalls() >> 33
    ms.getNumberOfSuccessfulCalls() >> 50

    when:
    Supplier<String> supplier = Decorators
      .ofSupplier{serviceCall("foobar")}
      .withCircuitBreaker(cb)
      .decorate()

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          errored false
        }
        span(1) {
          operationName "resilience4j"
          childOf(span(0))
          errored false
          tags {
            "$Tags.COMPONENT" "resilience4j"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_INTERNAL
            "resilience4j.circuit_breaker.name" "cb1"
            "resilience4j.circuit_breaker.state" "CLOSED"
            "resilience4j.circuit-breaker.metrics.failure_rate" 0.1f
            "resilience4j.circuit-breaker.metrics.slow_call_rate" 0.2f
            "resilience4j.circuit-breaker.metrics.buffered_calls" 12
            "resilience4j.circuit-breaker.metrics.failed_calls" 13
            "resilience4j.circuit-breaker.metrics.not_permitted_calls" 2
            "resilience4j.circuit-breaker.metrics.slow_calls" 23
            "resilience4j.circuit-breaker.metrics.slow_failed_calls" 3
            "resilience4j.circuit-breaker.metrics.slow_successful_calls" 33
            "resilience4j.circuit-breaker.metrics.successful_calls" 50
            defaultTags()
          }
        }
        span(2) {
          operationName "serviceCall"
          childOf(span(1))
          errored false
        }
      }
    }
  }

  def "decorateCheckedSupplier"() {
    when:
    CheckedSupplier<String> supplier = Decorators
      .ofCheckedSupplier { serviceCall("foobar") }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("cb"))
      .decorate()

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateCompletionStage"() {
    setup:
    def executor = Executors.newSingleThreadExecutor()
    Thread testThread = Thread.currentThread()
    when:
    Supplier<CompletionStage<String>> supplier = Decorators
      .ofCompletionStage{
        CompletableFuture.supplyAsync({
          // prevent completion on the same thread
          Thread.sleep(100)
          serviceCall("foobar")
        }, executor).whenComplete { r, e ->
          assert Thread.currentThread() != testThread,
          "Make sure that the thread running whenComplete is different from the one running the test. " +
          "This verifies that the scope we create does not cross the thread boundaries. " +
          "If it fails, ensure that the provided future isn't completed immediately. Otherwise, the callback will be called on the caller thread."
        }
      }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("cb"))
      .decorate()

    then:
    def future = runUnderTrace("parent"){supplier.get().toCompletableFuture()}
    future.get() == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateCheckedRunnable"() {
    when:
    CheckedRunnable runnable = Decorators
      .ofCheckedRunnable { serviceCall("foobar") }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("cb"))
      .decorate()

    then:
    runUnderTrace("parent") {
      runnable.run()
      "a"
    }
    and:
    assertExpectedTrace()
  }

  def "decorateCallable"() {
    when:
    Callable<String> callable = Decorators
      .ofCallable {serviceCall("foobar")}
      .withCircuitBreaker(CircuitBreaker.ofDefaults("cb"))
      .decorate()

    then:
    runUnderTrace("parent"){callable.call()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateSupplier"() {
    when:
    Supplier<String> supplier = Decorators
      .ofSupplier{serviceCall("foobar")}
      .withCircuitBreaker(CircuitBreaker.ofDefaults("cb"))
      .decorate()

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateFunction"() {
    when:
    Function<String, String> function = Decorators
      .ofFunction{v -> serviceCall("foobar-$v")}
      .withCircuitBreaker(CircuitBreaker.ofDefaults("cb"))
      .decorate()

    then:
    runUnderTrace("parent"){function.apply("test")} == "foobar-test"
    and:
    assertExpectedTrace()
  }

  def "decorateCheckedFunction"() {
    when:
    CheckedFunction<String, String> function = Decorators
      .ofCheckedFunction { v -> serviceCall("foobar-$v") }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("cb"))
      .decorate()

    then:
    runUnderTrace("parent") { function.apply("test") } == "foobar-test"
    and:
    assertExpectedTrace()
  }

  def "decorateConsumer"() {

    when:
    Consumer<String> consumer = Decorators
      .ofConsumer { s -> serviceCall(s) }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("cb"))
      .decorate()

    then:
    runUnderTrace("parent") {
      consumer.accept("test")
      "a"
    }
    and:
    assertExpectedTrace()
  }

  def "decorateCheckedConsumer"() {

    when:
    CheckedConsumer<String> consumer = CircuitBreaker.ofDefaults("cb").decorateCheckedConsumer { s -> serviceCall(s) }

    then:
    runUnderTrace("parent") {
      consumer.accept("test")
      "a"
    }
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

  def <T> T serviceCall(T value) {
    AgentTracer.startSpan("test", "serviceCall").finish()
    value
  }
}
