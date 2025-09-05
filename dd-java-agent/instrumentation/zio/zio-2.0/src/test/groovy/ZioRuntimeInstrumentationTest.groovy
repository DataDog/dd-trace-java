import datadog.trace.agent.test.InstrumentationSpecification

class ZioRuntimeInstrumentationTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.zio.experimental.enabled", "true")
  }

  def "trace is propagated to child fiber"() {
    setup:
    fixture().runNestedFibers()

    expect:
    assertTraces(1) {
      trace(2, true) {
        span(0) {
          operationName "fiber_1_span_1"
          parent()
        }
        span(1) {
          operationName "fiber_2_span_1"
          childOf(span(0))
        }
      }
    }
  }

  def "trace is preserved when fiber is interrupted"() {
    setup:
    fixture().runInterruptedFiber()

    expect:
    assertTraces(1) {
      trace(2, true) {
        span(0) {
          operationName "fiber_1_span_1"
          parent()
        }
        span(1) {
          operationName "fiber_2_span_1"
          childOf(span(0))
        }
      }
    }
  }

  def "synchronized fibers do not interfere with each other's traces"() {
    setup:
    fixture().runSynchronizedFibers()

    expect:
    assertTraces(2, SORT_TRACES_BY_NAMES) {
      trace(2, true) {
        span(0) {
          operationName "fiber_1_span_1"
          parent()
        }
        span(1) {
          operationName "fiber_1_span_2"
          childOf(span(0))
        }
      }
      trace(2, true) {
        span(0) {
          operationName "fiber_2_span_1"
          parent()
        }
        span(1) {
          operationName "fiber_2_span_2"
          childOf(span(0))
        }
      }
    }
  }

  def "concurrent fibers do not interfere with each other's traces"() {
    setup:
    fixture().runConcurrentFibers()

    expect:
    assertTraces(3, SORT_TRACES_BY_NAMES) {
      trace(2, true) {
        span(0) {
          operationName "fiber_1_span_1"
          parent()
        }
        span(1) {
          operationName "fiber_1_span_2"
          childOf(span(0))
        }
      }
      trace(2, true) {
        span(0) {
          operationName "fiber_2_span_1"
          parent()
        }
        span(1) {
          operationName "fiber_2_span_2"
          childOf(span(0))
        }
      }
      trace(2, true) {
        span(0) {
          operationName "fiber_3_span_1"
          parent()
        }
        span(1) {
          operationName "fiber_3_span_2"
          childOf(span(0))
        }
      }
    }
  }

  def "sequential fibers do not interfere with each other's traces"() {
    setup:
    fixture().runSequentialFibers()

    expect:
    assertTraces(3, SORT_TRACES_BY_NAMES) {
      trace(2, true) {
        span(0) {
          operationName "fiber_1_span_1"
          parent()
        }
        span(1) {
          operationName "fiber_1_span_2"
          childOf(span(0))
        }
      }
      trace(2, true) {
        span(0) {
          operationName "fiber_2_span_1"
          parent()
        }
        span(1) {
          operationName "fiber_2_span_2"
          childOf(span(0))
        }
      }
      trace(2, true) {
        span(0) {
          operationName "fiber_3_span_1"
          parent()
        }
        span(1) {
          operationName "fiber_3_span_2"
          childOf(span(0))
        }
      }
    }
  }

  def fixture() {
    ZioTestFixtures$.MODULE$
  }
}
