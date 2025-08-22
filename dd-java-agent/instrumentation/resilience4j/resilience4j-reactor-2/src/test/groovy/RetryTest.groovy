import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import reactor.core.publisher.ConnectableFlux
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class RetryTest extends AgentTestRunner {

  // TODO retry operation should create extra retry span when retry happens.
  //  currently it's not obvious when exactly the retry part of the resulted spans.

  // TODO test retry without failOnMaxAttempts

  def "decorateCompletionStage retry twice on error"() {
    setup:
    ConnectableFlux<String> connection = Flux.just("abc")
      .map({ serviceCallErr(it, new IllegalStateException("error"))})
      .transformDeferred(RetryOperator.of(Retry.of("R0", RetryConfig.custom().maxAttempts(2).build())))
      .publishOn(Schedulers.boundedElastic())
      .publish()

    when:
    connection.subscribe {
      // won't show up because of errors upstream
      runnableUnderTrace("child-" + it) {}
    }

    runnableUnderTrace("parent") {
      connection.connect()
    }

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
          operationName "R0"
          childOf span(0)
          errored false
        }
        span(2) {
          operationName "serviceCallErr/abc"
          childOf span(1)
          errored false
        }
        // second attempt span under the retry span
        span(3) {
          operationName "serviceCallErr/abc"
          childOf span(1)
          errored false
        }
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
