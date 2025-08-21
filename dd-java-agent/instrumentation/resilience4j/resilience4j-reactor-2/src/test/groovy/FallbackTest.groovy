import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.reactor.ReactorOperatorFallbackDecorator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import reactor.core.publisher.ConnectableFlux
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import spock.lang.Ignore

import java.time.Duration

import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class FallbackTest extends AgentTestRunner {

  @Ignore
  def "retry with fallback"() {
    setup:
    def retry = RetryOperator.of(Retry.of("R0", RetryConfig.custom().maxAttempts(1).build()))

    ConnectableFlux<String> connection = Flux
      .just("abc", "def")
      //      .map({ serviceCallErr(it, new IllegalStateException("error"))})
      .map({ it -> Flux.error(new IllegalStateException("error")) })
      //      .map({ it -> throw new IllegalStateException("error") })
      .transformDeferred(RetryOperator.of(Retry.of("R0", RetryConfig.custom().maxAttempts(2).build())))
      // TODO doesn't trigger fallback logic at all
      //      .transformDeferred(ReactorOperatorFallbackDecorator.decorateRetry(retry, Flux.just("Fallback").map({ it -> serviceCall(it) })))
      //      .publishOn(Schedulers.boundedElastic())
      .publish()

    when:
    connection.subscribe {
      runnableUnderTrace("child-" + it) {} // won't show up because of errors upstream
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
          parent()
          errored false
        }
        span(1) {
          operationName "fallback"
          childOf span(0)
          errored false
        }
        span(2) {
          operationName "R0"
          childOf span(1)
          errored false
        }
        span(3) {
          operationName "serviceCallErr/abc"
          childOf span(2)
          errored false
        }
        // second attempt span under the retry span
        //        span(4) {
        //          operationName "serviceCallErr/abc"
        //          childOf span(1)
        //          errored false
        //        }
      }
    }
  }

  def "fallback"() {
    setup:
    RetryConfig config = RetryConfig.<String>custom()
    .retryOnResult("retry"::equals) // retry when element is "retry"
    .waitDuration(Duration.ofMillis(10))
    .maxAttempts(2)
    .failAfterMaxAttempts(true)
    .build()
    Retry retry = Retry.of("R0", config)
    def fallback = Flux.just("Fallback").map({ it -> serviceCall(it) })
    ConnectableFlux<String> connection = Flux
    .just("retry", "abc")
    //    .map({it -> serviceCall("srv-" + it); return it})
    //      .map({ serviceCallErr(it, new IllegalStateException("error"))})
    // TODO should create a fallback span???
    .transformDeferred(ReactorOperatorFallbackDecorator.decorateRetry(RetryOperator.of(retry), fallback))
    //    .publishOn(Schedulers.boundedElastic()) // this result in reordered span ids breaking the assertions
    .publish()

    when:
    connection.subscribe {
      runnableUnderTrace("subscriber-" + it) {}
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
          parent()
          errored false
        }
        span(1) {
          operationName "fallback"
          childOf span(0)
          errored false
        }
        span(2) {
          operationName "R0"
          childOf span(1)
          errored false
        }
        span(3) {
          operationName "subscriber-retry"
          childOf span(2)
          errored false
        }
        span(4) {
          operationName "subscriber-abc"
          childOf span(2)
          errored false
        }
        span(5) {
          operationName "serviceCall/Fallback"
          childOf span(1)
          errored false
        }
        span(6) {
          operationName "subscriber-Fallback"
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
