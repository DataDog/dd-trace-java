

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.decorators.Decorators

import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class CircuitBreakerTest extends AgentTestRunner {

  def "decorateSupplier"() {
    when:
    Supplier<String> supplier = Decorators
    .ofSupplier(() -> serviceCall("foobar"))
    .withCircuitBreaker(CircuitBreaker.ofDefaults("id"))
    .decorate()

    then:
    runUnderTrace("parent", supplier::get) == "foobar"
    and:
    assertTraces(1) {
      trace(3) {
        span(0) {
          operationName "parent"
          parent()
          errored false
        }
        span(1) {
          operationName "resilience4j.circuit-breaker"
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

  <T> T serviceCall(T t) {
    AgentTracer.startSpan("test", "serviceCall").finish()
    t
  }
}
