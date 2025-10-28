import datadog.trace.agent.test.InstrumentationSpecification
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
import spock.lang.RepeatUntilFailure

class StackedOperatorsTest extends InstrumentationSpecification {

  @RepeatUntilFailure(maxAttempts = 100)
  def "test stacked operators retry(circuitbreaker)"() {
    setup:
    ConnectableFlux<String> connection = Flux
      .just("abc", "def")
      .map({serviceCall(it)})
      .transformDeferred(CircuitBreakerOperator.of(CircuitBreaker.ofDefaults("C2")))
      .transformDeferred(RetryOperator.of(Retry.of("R1", RetryConfig.custom().maxAttempts(3).build())))
      .publishOn(Schedulers.boundedElastic())
      .publish()

    when:
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
      }
    }
  }

  def <T> T serviceCall(T value) {
    AgentTracer.startSpan("test", "serviceCall/$value").finish()
    value
  }
}
