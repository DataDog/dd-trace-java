import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.Retry
import reactor.core.publisher.ConnectableFlux
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class RetryTest extends AgentTestRunner {

  def "test retry"() {
    ConnectableFlux<String> connection = Flux.just("foo", "bar")
      .transformDeferred(RetryOperator.of(Retry.ofDefaults("id")))
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
}
