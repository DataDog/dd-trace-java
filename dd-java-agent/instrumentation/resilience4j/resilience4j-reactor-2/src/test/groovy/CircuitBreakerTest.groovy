import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
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
      trace(5) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          errored false
        }
        span(1) {
          operationName "C1"
          childOf(span(0))
          errored false
        }
        span(2) {
          operationName "C2"
          childOf(span(1))
          errored false
        }
        span(3) {
          operationName "foo"
          childOf(span(2))
          errored false
        }
        span(4) {
          operationName "bar"
          childOf(span(2))
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
      trace(7) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          errored false
        }
        span(1) {
          operationName "C1"
          childOf(span(0))
          errored false
        }
        span(2) {
          operationName "C2"
          childOf(span(1))
          errored false
        }
        span(3) {
          operationName "serviceCall"
          childOf(span(2))
          errored false
        }
        span(4) {
          operationName "serviceCall"
          childOf(span(2))
          errored false
        }
        span(5) {
          operationName "foo"
          childOf(span(2))
          errored false
        }
        span(6) {
          operationName "bar"
          childOf(span(2))
          errored false
        }
      }
    }
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
