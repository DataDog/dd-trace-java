import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import dd.test.trace.annotation.DontTraceClass
import dd.test.trace.annotation.SayTracedHello
import dd.test.trace.annotation.TracedSubClass

import java.util.concurrent.Callable

class TraceAnnotationsTest extends InstrumentationSpecification {

  def "test simple case annotations"() {
    setup:
    // Test single span in new trace
    SayTracedHello.sayHello()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test simple case with only operation name set"() {
    setup:
    // Test single span in new trace
    SayTracedHello.sayHA()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHA"
          operationName "SAY_HA"
          spanType "DB"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test simple case with only resource name set"() {
    setup:
    // Test single span in new trace
    SayTracedHello.sayHelloOnlyResourceSet()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "test"
          resourceName "WORLD"
          operationName "trace.annotation"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test simple case with both resource and operation name set"() {
    setup:
    // Test single span in new trace
    SayTracedHello.sayHAWithResource()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "test"
          resourceName "EARTH"
          operationName "SAY_HA"
          spanType "DB"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
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
      trace(3) {
        span {
          resourceName "SayTracedHello.sayHELLOsayHA"
          operationName "NEW_TRACE"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          resourceName "SayTracedHello.sayHA"
          operationName "SAY_HA"
          spanType "DB"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
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
      trace(3) {
        span {
          resourceName "WORLD"
          operationName "NEW_TRACE"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          resourceName "SayTracedHello.sayHA"
          operationName "SAY_HA"
          spanType "DB"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
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
      trace(3) {
        span {
          resourceName "WORLD"
          operationName "NEW_TRACE"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          resourceName "EARTH"
          operationName "SAY_HA"
          spanType "DB"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test exception exit"() {
    setup:
    Throwable error = null
    try {
      SayTracedHello.sayERROR()
    } catch (final Throwable ex) {
      error = ex
    }

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "SayTracedHello.sayERROR"
          operationName "ERROR"
          errored true
          tags {
            "$Tags.COMPONENT" "trace"
            errorTags(error.class)
            defaultTags()
          }
        }
      }
    }
  }

  def "test exception exit with resource name"() {
    setup:
    Throwable error = null
    try {
      SayTracedHello.sayERRORWithResource()
    } catch (final Throwable ex) {
      error = ex
    }

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "WORLD"
          operationName "ERROR"
          errored true
          tags {
            "$Tags.COMPONENT" "trace"
            errorTags(error.class)
            defaultTags()
          }
        }
      }
    }
  }

  def "test anonymous class annotations"() {
    setup:
    // Test anonymous classes with package.
    SayTracedHello.fromCallable()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "SayTracedHello\$1.call"
          operationName "trace.annotation"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
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
      trace(1) {
        span {
          resourceName "SayTracedHello\$1.call"
          operationName "trace.annotation"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        trace(1) {
          span {
            resourceName "TraceAnnotationsTest\$1.call"
            operationName "trace.annotation"
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }
  }

  def "test simple inheritance"() {
    setup:
    def test = new TracedSubClass()
    when:
    // these produce spans because the default method in the interface and the non-abstract method in
    // the superclass were instrumented when those types were loaded and those methods aren't overridden
    test.testTracedDefaultMethod()
    test.testTracedSuperMethod()

    // these currently don't produce spans because the overridden methods are not annotated with @Trace
    // and method annotations are not inherited from the default/super methods
    test.testOverriddenTracedDefaultMethod()
    test.testOverriddenTracedSuperMethod()

    // these currently don't produce spans because the implemented methods are not annotated with @Trace
    // and method annotations are not inherited from the interface/abstract methods
    test.testTracedInterfaceMethod()
    test.testTracedAbstractMethod()

    then:
    assertTraces(2) {
      trace(1) {
        span {
          resourceName "TracedInterface.testTracedDefaultMethod"
          operationName "trace.annotation"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          resourceName "TracedSuperClass.testTracedSuperMethod"
          operationName "trace.annotation"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test measured attribute"() {
    setup:
    TraceUtils.runUnderTrace("parent", () -> {
      SayTracedHello.sayHello()
      SayTracedHello.sayHelloMeasured()
      SayTracedHello.sayHello()
    })

    expect:
    assertTraces(1) {
      trace(4) {
        span {
          hasServiceName()
          resourceName "parent"
          operationName "parent"
          parent()
          errored false
          tags {
            defaultTags()
          }
        }
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          childOf(span(0))
          errored false
          measured false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHelloMeasured"
          operationName "trace.annotation"
          childOf(span(0))
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          childOf(span(0))
          errored false
          measured false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test noParent attribute"() {
    setup:
    TraceUtils.runUnderTrace("parent", () -> {
      SayTracedHello.sayHello()
      SayTracedHello.sayHelloNoParent()
      SayTracedHello.sayHello()
    })

    expect:
    assertTraces(2) {
      trace(3) {
        span {
          hasServiceName()
          resourceName "parent"
          operationName "parent"
          parent()
          errored false
          tags {
            defaultTags()
          }
        }
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          childOf(span(0))
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "trace.annotation"
          childOf(span(0))
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHelloNoParent"
          operationName "trace.annotation"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }
  def "@DoNotTrace should mute tracing"() {
    setup:
    TraceUtils.runUnderTrace("parent", () -> {
      new DontTraceClass().muted()
    })
    expect:
    assertTraces(1) {
      trace(1) {
        span {
          hasServiceName()
          resourceName "parent"
          operationName "parent"
          parent()
          errored false
          tags {
            defaultTags()
          }
        }
      }
    }
  }
}
