import annotatedsample.latest.TracedMethods
import datadog.trace.bootstrap.instrumentation.api.Tags

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WithSpanAnnotationLatestDepTest extends WithSpanAnnotationTest {

  def "test WithSpan annotated method inheritContext=false"() {
    setup:
    runUnderTrace("parent") {
      TracedMethods.sayHelloWithInANewTrace()
    }
    expect:
    assertTraces(2) {
      trace(1) {
        basicSpan(it, "parent")
      }
      trace(1) {
        span {
          resourceName "TracedMethods.sayHelloWithInANewTrace"
          operationName "TracedMethods.sayHelloWithInANewTrace"
          parent()
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }
}
