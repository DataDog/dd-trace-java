import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import reactor.core.publisher.ConnectableFlux
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class CircuitBreakerTest extends InstrumentationSpecification {

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
      }
    }

    where:
    measuredEnabled | tagMetricsEnabled
    true            | true
    false           | false
    true            | false
    false           | true
  }

  def "test circuit-breaker with Flux"() {
    ConnectableFlux<String> connection = Flux.just("foo", "bar")
      .transformDeferred(CircuitBreakerOperator.of(CircuitBreaker.ofDefaults("C1")))
      .publishOn(Schedulers.boundedElastic())
      .publish()

    when:
    connection.subscribe {
      AgentTracer.startSpan("test", it).finish()
    }

    runnableUnderTrace("parent", {
      connection.connect()
    })

    then:
    assertTraces(1) {
      trace(4) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          errored false
        }
        span(1) {
          operationName "resilience4j"
          childOf(span(0))
          errored false
        }
        span(2) {
          operationName "foo"
          childOf(span(1))
          errored false
        }
        span(3) {
          operationName "bar"
          childOf(span(1))
          errored false
        }
      }
    }
  }

  def "test circuit-breaker with Mono"() {
    Mono<String> mono = Mono.just("abc")
      .transformDeferred(CircuitBreakerOperator.of(CircuitBreaker.ofDefaults("C2")))

    when:
    runnableUnderTrace("parent", {
      mono.subscribe {
        AgentTracer.startSpan("test", it).finish()
      }
    })

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
        }
        span(2) {
          operationName "abc"
          childOf(span(1))
          errored false
        }
      }
    }
  }
}
