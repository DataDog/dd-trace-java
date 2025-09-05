import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import reactor.core.publisher.ConnectableFlux
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class StackedOperatorsTest extends AgentTestRunner {

  def "test stacked operators retry(circuitbreaker(retry(circuitbreaker)))"() {
    setup:
    ConnectableFlux<String> connection = Flux
      .just("abc", "def")
      .map({ serviceCall(it)})
      //      .map({ serviceCallErr(it, new IllegalStateException("error"))})
      //      .transformDeferred(RetryOperator.of(Retry.of("R2", RetryConfig.custom().maxAttempts(3).build())))
      .transformDeferred(CircuitBreakerOperator.of(CircuitBreaker.ofDefaults("C2")))
      .transformDeferred(RetryOperator.of(Retry.of("R1", RetryConfig.custom().maxAttempts(3).build())))
      .transformDeferred(CircuitBreakerOperator.of(CircuitBreaker.ofDefaults("C1"))) // TODO test various combination of stacked operators
      .transformDeferred(RetryOperator.of(Retry.of("R0", RetryConfig.custom().maxAttempts(2).build())))
      .publishOn(Schedulers.boundedElastic())
      .publish()

    when:
    //    connection.subscribe {
    //      runnableUnderTrace("child-" + it) {} // won't show up because of errors upstream
    //    }

    runnableUnderTrace("parent", {
      connection.connect()
    })

    then:
    assertTraces(1) {
      trace(4) {
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
          operationName "serviceCall/abc"
          childOf span(1)
          errored false
        }
        span(3) {
          operationName "serviceCall/def"
          childOf span(1)
          errored false
        }
        //        span(5) {
        //          operationName "child-abc"
        //          childOf span(4)
        //          errored false
        //        }
        //        span(5) {
        //          operationName "child-def"
        //          childOf span(4)
        //          errored false
        //        }
      }
    }
  }

  def <T> T serviceCall(T value) {
    AgentTracer.startSpan("test", "serviceCall/$value").finish()
    System.err.println(">>> serviceCall/$value")
    value
  }

  void serviceCallErr(String value, IllegalStateException e) {
    def span = AgentTracer.startSpan("test", "serviceCallErr/$value")
    def scope = AgentTracer.activateSpan(span)
    try {
      throw e
    } finally {
      scope.close()
      span.finish()
    }
  }
}
