import datadog.trace.agent.test.InstrumentationSpecification
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import reactor.core.publisher.ConnectableFlux
import reactor.core.publisher.Flux

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class TraceCapturingTest extends InstrumentationSpecification {

  def "cold publisher"() {
    def cb1 = CircuitBreaker.ofDefaults("cb1")
    def flux = runUnderTrace("init") {
      Flux.range(1, 2)
      .map {
        v -> runUnderTrace("in" + v) {
          return v
        }
      }
      .transformDeferred(CircuitBreakerOperator.of(cb1))
    }

    when:
    (1..2).each {
      runUnderTrace("sub" + it) {
        flux.subscribe(v -> runnableUnderTrace("out" + v) {})
      }
    }

    then:
    assertTraces(3) {
      trace(1) {
        span(0) {
          operationName "init"
          errored false
        }
      }
      trace(6) {
        sortSpansByStart()
        span(0) {
          operationName "sub1"
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
          operationName "out1"
          childOf span(1)
          errored false
        }
        span(4) {
          operationName "in2"
          childOf span(1)
          errored false
        }
        span(5) {
          operationName "out2"
          childOf span(1)
          errored false
        }
      }
      trace(6) {
        sortSpansByStart()
        span(0) {
          operationName "sub2"
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
          operationName "out1"
          childOf span(1)
          errored false
        }
        span(4) {
          operationName "in2"
          childOf span(1)
          errored false
        }
        span(5) {
          operationName "out2"
          childOf span(1)
          errored false
        }
      }
    }
  }

  def "hot publisher with connect/replay"() {
    def cb1 = CircuitBreaker.ofDefaults("cb1")
    ConnectableFlux<Integer> conn = runUnderTrace("init") {
      Flux.range(1, 2)
      .map {
        v -> runUnderTrace("in" + v) {
          return v
        }
      }
      .transformDeferred(CircuitBreakerOperator.of(cb1))
      .publish() // or replay()
    }

    when:
    (1..2).each {
      runUnderTrace("sub" + it) {
        conn.subscribe(v -> runnableUnderTrace("out" + v) {})
      }
    }
    runnableUnderTrace("conn", {
      conn.connect()
    })

    then:
    assertTraces(4) {
      trace(1) {
        span(0) {
          operationName "init"
          errored false
        }
      }
      trace(1) {
        span(0) {
          operationName "sub1"
          errored false
        }
      }
      trace(1) {
        span(0) {
          operationName "sub2"
          errored false
        }
      }
      trace(8) {
        sortSpansByStart()
        span(0) {
          operationName "conn"
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
          operationName "out1"
          childOf span(1)
          errored false
        }
        span(4) {
          operationName "out1"
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
        span(7) {
          operationName "out2"
          childOf span(1)
          errored false
        }
      }
    }
  }

  def "hot publisher with autoConnect/refCount -- r4j spans connect to the subscriber that triggered auto-connect"() {
    def cb1 = CircuitBreaker.ofDefaults("cb1")
    Flux<Integer> flux = runUnderTrace("init") {
      Flux.range(1, 2)
      .map {
        v -> runUnderTrace("in" + v) {
          return v
        }
      }
      .transformDeferred(CircuitBreakerOperator.of(cb1))
      .publish()
      .autoConnect(2) // refCount
    }

    when:
    (1..3).each {
      runUnderTrace("sub" + it) {
        flux.subscribe(v -> runnableUnderTrace("out" + v) {
        })
      }
    }

    then:
    assertTraces(4) {
      sortSpansByStart()
      trace(1) {
        span(0) {
          operationName "init"
          errored false
        }
      }
      trace(1) {
        span(0) {
          operationName "sub1"
          errored false
        }
      }
      trace(8) {
        span(0) {
          operationName "sub2"
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
          operationName "out1"
          childOf span(1)
          errored false
        }
        span(4) {
          operationName "out1"
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
        span(7) {
          operationName "out2"
          childOf span(1)
          errored false
        }
      }
      trace(1) {
        span(0) {
          operationName "sub3"
          errored false
        }
      }
    }
  }
}

