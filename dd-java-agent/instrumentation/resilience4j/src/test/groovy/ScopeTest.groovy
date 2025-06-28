import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import reactor.core.publisher.ConnectableFlux
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class ScopeTest extends AgentTestRunner {

  def "test flux"() {
    // TODO instrument so there is a circuit-breaker span show up in the result

    ConnectableFlux<String> connection = Flux.just("foo", "bar")
      .map { it ->
        //        if (it == "err") {
        //          throw new IllegalStateException("test")
        //        }
        //        AgentTracer.startSpan("before").finish()
        it
      }
      //      .subscribeOn(Schedulers.boundedElastic())
      .transformDeferred(CircuitBreakerOperator.of(CircuitBreaker.ofDefaults("CB")))
      .publishOn(Schedulers.boundedElastic())
      //      .subscribeOn(Schedulers.boundedElastic())
      //      .map { it ->
      //        AgentTracer.startSpan("after").finish()
      //        it
      //      }
      .publish()
    when:

    new Thread({
      connection.subscribe {
        //        Thread.sleep(500)
        AgentTracer.startSpan("test", it).finish()
      }
    }).run()

    //    new Thread({
    runnableUnderTrace("parent", {
      connection.connect() // start the flux from a different thread
    })
    //      runnableUnderTrace("parent2", {
    //        connection.connect() // start the flux from a different thread
    //      })
    //    }).run()

    //    Thread.sleep(1000)
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
