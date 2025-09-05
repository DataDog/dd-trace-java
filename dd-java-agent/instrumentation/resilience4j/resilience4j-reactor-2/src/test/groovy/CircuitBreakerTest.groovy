import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import reactor.core.publisher.ConnectableFlux
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class CircuitBreakerTest extends AgentTestRunner {

  def "test circuit-breaker"() {
    // TODO add mono, error, and other tests
    ConnectableFlux<String> connection = Flux.just("foo", "bar")
      .transformDeferred(CircuitBreakerOperator.of(CircuitBreaker.ofDefaults("C2")))
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

  def "test circuit-breaker span before"() {
    // TODO add mono, error, and other tests
    ConnectableFlux<String> connection = Flux.just("foo", "bar")
      .map({ it -> serviceCall(it) }) // serviceCall span is under r4j
      .transformDeferred(CircuitBreakerOperator.of(CircuitBreaker.ofDefaults("C2")))
      .transformDeferred(CircuitBreakerOperator.of(CircuitBreaker.ofDefaults("C1")))
      .publishOn(Schedulers.boundedElastic())
      .publish()

    when:
    connection.subscribe {
      AgentTracer.startSpan("test", it).finish() // these spans are under r4j as well. UPDATE: NOPE, they're not and shouldn't be
    }

    runnableUnderTrace("parent", {
      connection.connect()
    })

    then:
    assertTraces(1) {
      trace(6) {
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
          operationName "serviceCall"
          childOf(span(1))
          errored false
        }
        span(3) {
          operationName "serviceCall"
          childOf(span(1))
          errored false
        }
        span(4) {
          operationName "foo"
          childOf(span(1))
          errored false
        }
        span(5) {
          operationName "bar"
          childOf(span(1))
          errored false
        }
      }
    }
  }

  def "test circuit-breaker state reporting"() {
    def cb = Mock(CircuitBreaker)
    cb.getState() >> state
    cb.tryAcquirePermission() >> false

    ConnectableFlux<String> connection = Flux.just("foo", "bar")
      .transformDeferred(CircuitBreakerOperator.of(cb))
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
            "resilience4j.circuit_breaker.state" state.toString()
            defaultTags()
          }
        }
      }
    }

    where:
    state << CircuitBreaker.State.values()
  }

  def <T> T serviceCall(T value) {
    AgentTracer.startSpan("test", "serviceCall").finish()
    value
  }

  void serviceCallErr(IllegalStateException e) {
    def span = AgentTracer.startSpan("test", "serviceCall")
    def scope = AgentTracer.activateSpan(span)
    try {
      throw e
    } finally {
      scope.close()
      span.finish()
    }
  }
}
