import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.circuitbreaker.CircuitBreaker

import java.util.concurrent.Callable
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class CircuitBreakerTest extends InstrumentationSpecification {

  def "decorate supplier with circuit breaker"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_MEASURED_ENABLED, measuredEnabled.toString())
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_TAG_METRICS_ENABLED, tagMetricsEnabled.toString())

    def metrics = Mock(CircuitBreaker.Metrics)
    def circuitBreaker = Mock(CircuitBreaker)
    circuitBreaker.getName() >> "circuit-breaker-1"
    circuitBreaker.getState() >> CircuitBreaker.State.CLOSED
    circuitBreaker.getMetrics() >> metrics
    metrics.getFailureRate() >> 15.5f
    metrics.getSlowCallRate() >> 5.2f
    metrics.getNumberOfBufferedCalls() >> 100
    metrics.getNumberOfFailedCalls() >> 15
    metrics.getNumberOfSlowCalls() >> 5

    when:
    Supplier<String> supplier = CircuitBreaker.decorateSupplier(circuitBreaker) { serviceCall("result") }

    then:
    runUnderTrace("parent") { supplier.get() } == "result"

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
            "resilience4j.circuit_breaker.name" "circuit-breaker-1"
            "resilience4j.circuit_breaker.state" "CLOSED"
            if (tagMetricsEnabled) {
              "resilience4j.circuit_breaker.metrics.failure_rate" 15.5f
              "resilience4j.circuit_breaker.metrics.slow_call_rate" 5.2f
              "resilience4j.circuit_breaker.metrics.buffered_calls" 100
              "resilience4j.circuit_breaker.metrics.failed_calls" 15
              "resilience4j.circuit_breaker.metrics.slow_calls" 5
            }
          }
        }
        span(2) {
          operationName "service-call"
          childOf(span(1))
          errored false
        }
      }
    }

    where:
    measuredEnabled | tagMetricsEnabled
    false           | false
    false           | true
    true            | false
    true            | true
  }

  def "circuit breaker in open state"() {
    setup:
    def metrics = Mock(CircuitBreaker.Metrics)
    def circuitBreaker = Mock(CircuitBreaker)
    circuitBreaker.getName() >> "circuit-breaker-open"
    circuitBreaker.getState() >> CircuitBreaker.State.OPEN
    circuitBreaker.getMetrics() >> metrics
    metrics.getFailureRate() >> 75.0f
    metrics.getSlowCallRate() >> 0.0f

    when:
    Supplier<String> supplier = CircuitBreaker.decorateSupplier(circuitBreaker) { serviceCall("result") }

    then:
    runUnderTrace("parent") { supplier.get() } == "result"

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
        }
        span(1) {
          operationName "resilience4j"
          childOf(span(0))
          tags {
            "resilience4j.circuit_breaker.name" "circuit-breaker-open"
            "resilience4j.circuit_breaker.state" "OPEN"
          }
        }
        span(2) {
          operationName "service-call"
          childOf(span(1))
        }
      }
    }
  }

  def "circuit breaker in half-open state"() {
    setup:
    def circuitBreaker = Mock(CircuitBreaker)
    circuitBreaker.getName() >> "circuit-breaker-half-open"
    circuitBreaker.getState() >> CircuitBreaker.State.HALF_OPEN
    circuitBreaker.getMetrics() >> Mock(CircuitBreaker.Metrics)

    when:
    Supplier<String> supplier = CircuitBreaker.decorateSupplier(circuitBreaker) { serviceCall("result") }

    then:
    runUnderTrace("parent") { supplier.get() } == "result"

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
        }
        span(1) {
          operationName "resilience4j"
          childOf(span(0))
          tags {
            "resilience4j.circuit_breaker.name" "circuit-breaker-half-open"
            "resilience4j.circuit_breaker.state" "HALF_OPEN"
          }
        }
        span(2) {
          operationName "service-call"
          childOf(span(1))
        }
      }
    }
  }

  def "decorate callable with circuit breaker"() {
    setup:
    def circuitBreaker = Mock(CircuitBreaker)
    circuitBreaker.getName() >> "circuit-breaker-callable"
    circuitBreaker.getState() >> CircuitBreaker.State.CLOSED
    circuitBreaker.getMetrics() >> Mock(CircuitBreaker.Metrics)

    when:
    Callable<String> callable = CircuitBreaker.decorateCallable(circuitBreaker) { serviceCall("callable-result") }

    then:
    runUnderTrace("parent") { callable.call() } == "callable-result"

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
        }
        span(1) {
          operationName "resilience4j"
          childOf(span(0))
          tags {
            "resilience4j.circuit_breaker.name" "circuit-breaker-callable"
            "resilience4j.circuit_breaker.state" "CLOSED"
          }
        }
        span(2) {
          operationName "service-call"
          childOf(span(1))
        }
      }
    }
  }

  def "decorate runnable with circuit breaker"() {
    setup:
    def circuitBreaker = Mock(CircuitBreaker)
    circuitBreaker.getName() >> "circuit-breaker-runnable"
    circuitBreaker.getState() >> CircuitBreaker.State.CLOSED
    circuitBreaker.getMetrics() >> Mock(CircuitBreaker.Metrics)

    when:
    Runnable runnable = CircuitBreaker.decorateRunnable(circuitBreaker) {
      serviceCall("runnable-executed")
    }

    then:
    runUnderTrace("parent") { runnable.run() }

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
        }
        span(1) {
          operationName "resilience4j"
          childOf(span(0))
          tags {
            "resilience4j.circuit_breaker.name" "circuit-breaker-runnable"
            "resilience4j.circuit_breaker.state" "CLOSED"
          }
        }
        span(2) {
          operationName "service-call"
          childOf(span(1))
        }
      }
    }
  }

  String serviceCall(String value) {
    AgentTracer.get().startSpan("service-call").finish()
    return value
  }
}
