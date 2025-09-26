import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.core.functions.CheckedConsumer
import io.github.resilience4j.core.functions.CheckedRunnable
import io.github.resilience4j.core.functions.CheckedSupplier
import io.github.resilience4j.core.functions.CheckedFunction

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class CircuitBreakerTest extends InstrumentationSpecification {
  static singleThreadExecutor = Executors.newSingleThreadExecutor()

  def "decorate span with circuit-breaker"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_MEASURED_ENABLED, measuredEnabled.toString())
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_TAG_METRICS_ENABLED, tagMetricsEnabled.toString())

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
    Supplier<String> supplier = CircuitBreaker.decorateSupplier(cb) { serviceCall("foobar") }

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
          measured measuredEnabled
          tags {
            "$Tags.COMPONENT" "resilience4j"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_INTERNAL
            "resilience4j.circuit_breaker.name" "cb1"
            "resilience4j.circuit_breaker.state" "CLOSED"
            if (tagMetricsEnabled) {
              "resilience4j.circuit_breaker.metrics.failure_rate" 0.1f
              "resilience4j.circuit_breaker.metrics.slow_call_rate" 0.2f
              "resilience4j.circuit_breaker.metrics.buffered_calls" 12
              "resilience4j.circuit_breaker.metrics.failed_calls" 13
              "resilience4j.circuit_breaker.metrics.not_permitted_calls" 2
              "resilience4j.circuit_breaker.metrics.slow_calls" 23
              "resilience4j.circuit_breaker.metrics.slow_failed_calls" 3
              "resilience4j.circuit_breaker.metrics.slow_successful_calls" 33
              "resilience4j.circuit_breaker.metrics.successful_calls" 50
            }
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

    where:
    measuredEnabled | tagMetricsEnabled
    true            | true
    false           | false
    true            | false
    false           | true
  }

  def "decorateCheckedSupplier"() {
    when:
    CheckedSupplier<String> supplier = CircuitBreaker.decorateCheckedSupplier(CircuitBreaker.ofDefaults("cb")) { serviceCall("foobar") }

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateCompletionStage"() {
    when:
    Supplier<CompletionStage<String>> supplier = CircuitBreaker.decorateCompletionStage(CircuitBreaker.ofDefaults("cb"), {
      CompletableFuture.supplyAsync({
        serviceCall("foobar")
      }, singleThreadExecutor)
    })
    def future = runUnderTrace("parent"){supplier.get()}.toCompletableFuture()

    then:
    future.get() == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateCheckedRunnable"() {
    when:
    CheckedRunnable runnable = CircuitBreaker.decorateCheckedRunnable(CircuitBreaker.ofDefaults("cb")) { serviceCall("foobar") }

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
    Callable<String> callable = CircuitBreaker.decorateCallable(CircuitBreaker.ofDefaults("cb")) { serviceCall("foobar") }

    then:
    runUnderTrace("parent"){callable.call()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateSupplier"() {
    when:
    Supplier<String> supplier = CircuitBreaker.decorateSupplier(CircuitBreaker.ofDefaults("cb")) { serviceCall("foobar") }

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateConsumer"() {

    when:
    Consumer<String> consumer = CircuitBreaker.decorateConsumer(CircuitBreaker.ofDefaults("cb")) { s -> serviceCall(s) }

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
    CheckedConsumer<String> consumer = CircuitBreaker.decorateCheckedConsumer(CircuitBreaker.ofDefaults("cb")) { s -> serviceCall(s) }

    then:
    runUnderTrace("parent") {
      consumer.accept("test")
      "a"
    }
    and:
    assertExpectedTrace()
  }

  def "decorateRunnable"() {
    when:
    Runnable runnable = CircuitBreaker.decorateRunnable(CircuitBreaker.ofDefaults("cb")) { serviceCall("foobar") }

    then:
    runUnderTrace("parent") {
      runnable.run()
      "a"
    }
    and:
    assertExpectedTrace()
  }

  def "decorateFunction"() {
    when:
    Function<String, String> function = CircuitBreaker.decorateFunction(CircuitBreaker.ofDefaults("cb")) { v -> serviceCall("foobar-$v") }

    then:
    runUnderTrace("parent"){function.apply("test")} == "foobar-test"
    and:
    assertExpectedTrace()
  }

  def "decorateCheckedFunction"() {
    when:
    CheckedFunction<String, String> function = CircuitBreaker.decorateCheckedFunction(CircuitBreaker.ofDefaults("cb")) { v -> serviceCall("foobar-$v") }

    then:
    runUnderTrace("parent") { function.apply("test") } == "foobar-test"
    and:
    assertExpectedTrace()
  }

  def "decorateFuture"() {
    setup:
    def executor = singleThreadExecutor
    when:
    Supplier<Future<String>> supplier = CircuitBreaker.decorateFuture(CircuitBreaker.ofDefaults("cb"), {
      CompletableFuture.supplyAsync({
        serviceCall("foobar")
      }, executor)
    })

    then:
    def future = runUnderTrace("parent"){supplier.get()}
    future.get() == "foobar"
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
