import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.trace_annotation.TraceAnnotationsInstrumentation
import dd.test.trace.annotation.SayTracedHello

import java.util.concurrent.Callable

import static datadog.trace.instrumentation.trace_annotation.TraceAnnotationsInstrumentation.DEFAULT_ANNOTATIONS

class ConfiguredTraceAnnotationsTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.trace.annotations", "package.Class\$Name;${OuterClass.InterestingMethod.name}")
  }

  def "test disabled nr annotation"() {
    setup:
    SayTracedHello.fromCallableWhenDisabled()

    expect:
    TEST_WRITER == []
  }

  def "test custom annotation based trace"() {
    expect:
    new AnnotationTracedCallable().call() == "Hello!"

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "AnnotationTracedCallable.call"
          operationName "trace.annotation"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test configuration #value"() {
    setup:
    if (value) {
      injectSysConfig("dd.trace.annotations", value)
    } else {
      removeSysConfig("dd.trace.annotations")
    }

    expect:
    new TraceAnnotationsInstrumentation().annotations == expected.toSet()

    where:
    value                               | expected
    null                                | [
      "datadog.trace.api.Trace",
      "com.newrelic.api.agent.Trace",
      "kamon.annotation.Trace",
      "com.tracelytics.api.ext.LogMethod",
      "io.opentracing.contrib.dropwizard.Trace",
      "org.springframework.cloud.sleuth.annotation.NewSpan"
    ]
    " "                                 | ["datadog.trace.api.Trace"]
    "some.Invalid[]"                    | ["datadog.trace.api.Trace"]
    "some.package.ClassName "           | ["datadog.trace.api.Trace", "some.package.ClassName"]
    " some.package.Class\$Name"         | ["datadog.trace.api.Trace", "some.package.Class\$Name"]
    "  ClassName  "                     | ["datadog.trace.api.Trace", "ClassName"]
    "ClassName"                         | ["datadog.trace.api.Trace", "ClassName"]
    "Class\$1;Class\$2;"                | ["datadog.trace.api.Trace", "Class\$1", "Class\$2"]
    "Duplicate ;Duplicate ;Duplicate; " | ["datadog.trace.api.Trace", "Duplicate"]
  }

  class AnnotationTracedCallable implements Callable<String> {
    @OuterClass.InterestingMethod
    @Override
    String call() throws Exception {
      return "Hello!"
    }
  }
}
