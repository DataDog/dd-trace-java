import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.instrumentation.trace_annotation.TraceAnnotationsInstrumentation
import dd.test.trace.annotation.SayTracedHello

import java.util.concurrent.Callable

import static datadog.trace.instrumentation.trace_annotation.TraceAnnotationsInstrumentation.DEFAULT_ANNOTATIONS

class ConfiguredTraceAnnotationsTest extends AgentTestRunner {
  static {
    PRE_AGENT_SYS_PROPS = ["dd.trace.annotations": "package.Class\$Name;${OuterClass.InterestingMethod.name}"]
  }

  def "test disabled nr annotation"() {
    setup:
    SayTracedHello.fromCallableWhenDisabled()

    expect:
    TEST_WRITER == []
  }

  def "test custom annotation based trace"() {
    when:
    def result = ConfigUtils.withNewConfig {
      new AnnotationTracedCallable().call()
    }

    then:
    result == "Hello!"
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          resourceName "AnnotationTracedCallable.call"
          operationName "trace.annotation"
        }
      }
    }
  }

  def "test configuration #value"() {
    setup:
    if (value) {
      System.properties.setProperty("dd.trace.annotations", value)
    } else {
      System.clearProperty("dd.trace.annotations")
    }

    when:
    Set result = ConfigUtils.withNewConfig {
      new TraceAnnotationsInstrumentation().additionalTraceAnnotations
    }

    then:
    result == expected.toSet()

    where:
    value                               | expected
    null                                | DEFAULT_ANNOTATIONS.toList()
    " "                                 | []
    "some.Invalid[]"                    | []
    "some.package.ClassName "           | ["some.package.ClassName"]
    " some.package.Class\$Name"         | ["some.package.Class\$Name"]
    "  ClassName  "                     | ["ClassName"]
    "ClassName"                         | ["ClassName"]
    "Class\$1;Class\$2;"                | ["Class\$1", "Class\$2"]
    "Duplicate ;Duplicate ;Duplicate; " | ["Duplicate"]
  }

  class AnnotationTracedCallable implements Callable<String> {
    @OuterClass.InterestingMethod
    @Override
    String call() throws Exception {
      return "Hello!"
    }
  }
}
