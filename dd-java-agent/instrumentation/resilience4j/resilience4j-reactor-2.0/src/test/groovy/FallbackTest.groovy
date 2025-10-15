import datadog.trace.agent.test.InstrumentationSpecification
import io.github.resilience4j.reactor.ReactorOperatorFallbackDecorator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class FallbackTest extends InstrumentationSpecification {

  def "Flux Retry Fallback"() {
    setup:
    RetryConfig config = RetryConfig.custom()
    .retryOnResult(1::equals) // retry when element is greater than 1
    .maxAttempts(2)
    .failAfterMaxAttempts(true)
    .build()
    Retry retry = Retry.of("R0", config)
    def fallback = Flux.just(-1, -2).map({ v -> runUnderTrace("in"+v) { v } })

    def retryOperator = ReactorOperatorFallbackDecorator.decorateRetry(RetryOperator.of(retry), fallback)
    Flux<Integer> flux = Flux
    .just(1, 2).map({ v -> runUnderTrace("in"+v) { v } })
    .transformDeferred(retryOperator)

    when:
    runnableUnderTrace("parent") {
      flux.subscribe(v -> runnableUnderTrace("out" + v) {})
    }

    then:
    assertTraces(1) {
      trace(11) {
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
          operationName "in1"
          childOf span(1)
          errored false
        }
        span(3) {
          operationName "in1" // retry second attempt
          childOf span(1)
          errored false
        }
        span(4) {
          operationName "out1" // only one out1 for two in1 attempts
          childOf span(1)
          errored false
        }
        span(5) {
          operationName "in2"
          childOf span(1)
          errored false
        }
        span(6) {
          operationName "out2"
          childOf span(1)
          errored false
        }
        // fallback elements go after all Flux elements
        span(7) {
          operationName "in-1"
          childOf span(1)
          errored false
        }
        span(8) {
          operationName "out-1"
          childOf span(1)
          errored false
        }
        span(9) {
          operationName "in-2"
          childOf span(1)
          errored false
        }
        span(10) {
          operationName "out-2"
          childOf span(1)
          errored false
        }
      }
    }
  }

  def "Mono Retry Fallback"() {
    setup:
    RetryConfig config = RetryConfig.<String>custom()
    .retryOnResult(1::equals) // retry when element is "retry"
    .maxAttempts(2)
    .failAfterMaxAttempts(true)
    .build()
    Retry retry = Retry.of("R0", config)
    def fallback = Mono.just(-1).map({ v -> runUnderTrace("in"+v) { v } })

    def retryOperator = ReactorOperatorFallbackDecorator.decorateRetry(RetryOperator.of(retry), fallback)
    Mono<Integer> source = Mono
    .just(1).map({ v -> runUnderTrace("in"+v) { v } })
    .transformDeferred(retryOperator)

    when:
    runnableUnderTrace("parent") {
      source.subscribe(v -> runnableUnderTrace("out" + v) {})
    }

    then:
    assertTraces(1) {
      trace(6) {
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
        span(2) { // first attempt
          operationName "in1"
          childOf span(1)
          errored false
        }
        span(3) { // second attempt
          operationName "in1"
          childOf span(1)
          errored false
        }
        span(4) {// fallback
          operationName "in-1"
          childOf span(1)
          errored false
        }
        span(5) {
          operationName "out-1"
          childOf span(1)
          errored false
        }
      }
    }
  }
}
