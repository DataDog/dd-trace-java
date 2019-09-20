import datadog.opentracing.decorators.ErrorFlag
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import dd.test.trace.annotation.SayTracedHello
import io.opentracing.tag.Tags

import java.util.concurrent.Callable

class TraceAnnotationsTest extends AgentTestRunner {
  def "test simple case annotations"() {
    when:
    // Test single span in new trace
    SayTracedHello.sayHello()

    then:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test simple case with only operation name set"() {
    when:
    // Test single span in new trace
    SayTracedHello.sayHA()

    then:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "test"
          resourceName "SayTracedHello.sayHA"
          operationName "SAY_HA"
          spanType "DB"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test simple case with only resource name set"() {
    when:
    // Test single span in new trace
    SayTracedHello.sayHelloOnlyResourceSet()

    then:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "test"
          resourceName "WORLD"
          operationName "trace.annotation"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test simple case with both resource and operation name set"() {
    when:
    // Test single span in new trace
    SayTracedHello.sayHAWithResource()

    then:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "test"
          resourceName "EARTH"
          operationName "SAY_HA"
          spanType "DB"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test complex case annotations"() {
    when:
    // Test new trace with 2 children spans
    SayTracedHello.sayHELLOsayHA()

    then:
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          resourceName "SayTracedHello.sayHELLOsayHA"
          operationName "NEW_TRACE"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
        span(1) {
          resourceName "SayTracedHello.sayHA"
          operationName "SAY_HA"
          spanType "DB"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
        span(2) {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test complex case with resource name at top level"() {
    when:
    // Test new trace with 2 children spans
    SayTracedHello.sayHELLOsayHAWithResource()

    then:
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          resourceName "WORLD"
          operationName "NEW_TRACE"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
        span(1) {
          resourceName "SayTracedHello.sayHA"
          operationName "SAY_HA"
          spanType "DB"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
        span(2) {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test complex case with resource name at various levels"() {
    when:
    // Test new trace with 2 children spans
    SayTracedHello.sayHELLOsayHAMixedResourceChildren()

    then:
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          resourceName "WORLD"
          operationName "NEW_TRACE"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
        span(1) {
          resourceName "EARTH"
          operationName "SAY_HA"
          spanType "DB"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
        span(2) {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT.key" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test exception exit"() {
    setup:
    TEST_TRACER.addDecorator(new ErrorFlag())

    when:
    SayTracedHello.sayERROR()

    then:
    def ex = thrown(RuntimeException)

    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          resourceName "SayTracedHello.sayERROR"
          operationName "ERROR"
          errored true
          tags {
            "$Tags.COMPONENT.key" "trace"
            errorTags(ex.class)
            defaultTags()
          }
        }
      }
    }
  }

  def "test exception exit with resource name"() {
    setup:

    TEST_TRACER.addDecorator(new ErrorFlag())

    when:
    SayTracedHello.sayERRORWithResource()

    then:
    def ex = thrown(RuntimeException)

    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          resourceName "WORLD"
          operationName "ERROR"
          errored true
          tags {
            "$Tags.COMPONENT.key" "trace"
            errorTags(ex.class)
            defaultTags()
          }
        }
      }
    }
  }

  def "test annonymous class annotations"() {
    when:
    // Test anonymous classes with package.
    SayTracedHello.fromCallable()

    then:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          resourceName "SayTracedHello\$1.call"
          operationName "trace.annotation"
        }
      }
    }

    when:
    // Test anonymous classes with no package.
    new Callable<String>() {
      @Trace
      @Override
      String call() throws Exception {
        return "Howdy!"
      }
    }.call()
    TEST_WRITER.waitForTraces(2)

    then:
    assertTraces(2) {
      trace(0, 1) {
        span(0) {
          resourceName "SayTracedHello\$1.call"
          operationName "trace.annotation"
        }
        trace(1, 1) {
          span(0) {
            resourceName "TraceAnnotationsTest\$1.call"
            operationName "trace.annotation"
          }
        }
      }
    }
  }
}
