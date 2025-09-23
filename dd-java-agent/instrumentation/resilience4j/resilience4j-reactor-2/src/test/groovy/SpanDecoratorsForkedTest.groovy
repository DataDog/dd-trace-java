import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import reactor.core.publisher.Flux

import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class SpanDecoratorsForkedTest extends InstrumentationSpecification {

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

    Flux<String> flux = Flux.just("foo", "bar")
      .transformDeferred(CircuitBreakerOperator.of(cb))

    when:
    runnableUnderTrace("parent", {
      flux.subscribe()
    })

    then:
    assertTraces(1) {
      trace(2) {
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
      }
    }
  }

  def "decorate span with retry"() {
    def ms = Mock(Retry.Metrics)
    def rc = Mock(RetryConfig)
    def rt = Mock(Retry)
    def cx = Mock(Retry.Context)
    rt.getName() >> "rt1"
    rt.getRetryConfig() >> rc
    rt.getMetrics() >> ms
    rt.context() >> cx
    rc.getMaxAttempts() >> 23
    rc.isFailAfterMaxAttempts() >> true
    ms.getNumberOfFailedCallsWithoutRetryAttempt() >> 1
    ms.getNumberOfFailedCallsWithRetryAttempt() >> 2
    ms.getNumberOfSuccessfulCallsWithoutRetryAttempt() >> 3
    ms.getNumberOfSuccessfulCallsWithRetryAttempt() >> 4

    when:
    Flux<String> connection = Flux.just("abc")
      .map({ serviceCall(it)})
      .transformDeferred(RetryOperator.of(rt))
      .publish()

    runnableUnderTrace("parent") {
      connection.connect()
    }

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
            "resilience4j.retry.name" "rt1"
            "resilience4j.retry.max_attempts" 23
            "resilience4j.retry.fail_after_max_attempts" true
            "resilience4j.retry.metrics.failed_without_retry" 1
            "resilience4j.retry.metrics.failed_with_retry" 2
            "resilience4j.retry.metrics.success_without_retry" 3
            "resilience4j.retry.metrics.success_with_retry" 4
            defaultTags()
          }
        }
        span(2) {
          operationName "serviceCall/abc"
          childOf span(1)
          errored false
        }
      }
    }
  }
}
