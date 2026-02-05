import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

class MicrometerContextPropagationTest extends InstrumentationSpecification {

  def "context is propagated when using publishOn"() {
    when:
    runUnderTrace("parent") {
      Mono.fromCallable {
        return "value"
      }
      .publishOn(Schedulers.boundedElastic())
      .map { value ->
        runUnderTrace("child") {
          return value.toUpperCase()
        }
      }
      .block()
    }

    then:
    assertTraces(1) {
      trace(2) {
        span {
          operationName "parent"
          resourceName "parent"
        }
        span {
          operationName "child"
          resourceName "child"
          childOf(span(0))
        }
      }
    }
  }

  def "context is propagated through Reactor flatMap with scheduler switch"() {
    when:
    runUnderTrace("parent") {
      Mono.just("test")
        .flatMap { value ->
          Mono.fromCallable {
            runUnderTrace("inner") {
              return value.toUpperCase()
            }
          }
          .subscribeOn(Schedulers.boundedElastic())
        }
        .block()
    }

    then:
    assertTraces(1) {
      trace(2) {
        span {
          operationName "parent"
          resourceName "parent"
        }
        span {
          operationName "inner"
          resourceName "inner"
          childOf(span(0))
        }
      }
    }
  }
}
