import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import dd.test.trace.annotation.SayTracedHello
import dd.test.trace.annotation.TracedSubClass

import java.util.concurrent.Callable

class TraceAnnotationNewOpNameForkedTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    injectSysConfig("dd.trace.annotations.legacy.tracing.enabled", "false")
  }
  def "test simple case annotations with new operation name"() {
    setup:
    // Test single span in new trace
    SayTracedHello.sayHello()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "test"
          resourceName "SayTracedHello.sayHello"
          operationName "SayTracedHello.sayHello"
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
  def "test simple case with only resource name set with new operation name"() {
    setup:
    // Test single span in new trace
    SayTracedHello.sayHelloOnlyResourceSet()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "test"
          resourceName "WORLD"
          operationName "SayTracedHello.sayHelloOnlyResourceSet"
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
  def "test anonymous class annotations with new operation name"() {
    setup:
    // Test anonymous classes with package.
    SayTracedHello.fromCallable()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "SayTracedHello\$1.call"
          operationName "SayTracedHello\$1.call"
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
          operationName "SayTracedHello\$1.call"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        trace(1) {
          span {
            resourceName "TraceAnnotationNewOpNameForkedTest\$1.call"
            operationName "TraceAnnotationNewOpNameForkedTest\$1.call"
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }
  }
  def "test simple inheritance with new operation name"() {
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
          operationName "TracedInterface.testTracedDefaultMethod"
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
          operationName "TracedSuperClass.testTracedSuperMethod"
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
}
