import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import datadog.trace.opentelemetry1.OtelSpan
import datadog.trace.opentelemetry1.OtelSpanBuilder
import datadog.trace.opentelemetry1.OtelTracer
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import spock.lang.Subject

import static io.opentelemetry.api.trace.StatusCode.ERROR
import static io.opentelemetry.api.trace.StatusCode.OK
import static io.opentelemetry.api.trace.StatusCode.UNSET

class OpenTelemetryTest extends AgentTestRunner {
  @Subject
  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("some-instrumentation")
  //  def httpPropagator = OpenTelemetry.getPropagators().httpTextFormat

  //  @Override
  //  void configurePreAgent() {
  //    super.configurePreAgent()
  // Required if instrumentation is not enabled by default
  //    injectSysConfig("dd.integration.opentelemetry.enabled", "true")
  //  }

  def "test injection"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def result = builder.startSpan()

    expect:
    tracer instanceof OtelTracer
    builder instanceof OtelSpanBuilder
    result instanceof OtelSpan
  }

  def "test span attributes"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    if (tagBuilder) {
      builder.setAttribute(DDTags.RESOURCE_NAME, "some-resource")
        .setAttribute("string", "a")
        .setAttribute("number", 1)
        .setAttribute("boolean", true)
    }
    def result = builder.startSpan()
    if (tagSpan) {
      result.setAttribute(DDTags.RESOURCE_NAME, "other-resource")
      result.setAttribute("string", "b")
      result.setAttribute("number", 2)
      result.setAttribute("boolean", false)
    }

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "some-name"
          if (tagSpan) {
            resourceName "other-resource"
          } else if (tagBuilder) {
            resourceName "some-resource"
          } else {
            resourceName "some-instrumentation"
          }
          errored false
          tags {
            if (tagSpan) {
              "string" "b"
              "number" 2
              "boolean" false
            } else if (tagBuilder) {
              "string" "a"
              "number" 1
              "boolean" true
            }
            defaultTags()
          }
        }
      }
    }

    where:
    tagBuilder | tagSpan
    true       | false
    true       | true
    false      | false
    false      | true
  }

  def "test span error status"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def result = builder.startSpan()

    when:
    result.setStatus(ERROR, "some-error")
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "some-name"
          resourceName "some-instrumentation"
          errored true

          tags {
            "$DDTags.ERROR_MSG" "some-error"
            defaultTags()
          }
        }
      }
    }
  }

  def "test span status transition"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def result = builder.startSpan()
    result.setStatus(UNSET)

    expect:
    !result.delegate.isError()
    result.delegate.getTag(DDTags.ERROR_MSG) == null

    when:
    result.setStatus(StatusCode.ERROR, "some error")

    then:
    result.delegate.isError()
    result.delegate.getTag(DDTags.ERROR_MSG) == "some error"

    when:
    result.setStatus(UNSET)

    then:
    result.delegate.isError()
    result.delegate.getTag(DDTags.ERROR_MSG) == "some error"

    when:
    result.setStatus(OK)

    then:
    !result.delegate.isError()
    result.delegate.getTag(DDTags.ERROR_MSG) == null

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "some-name"
          resourceName "some-instrumentation"
          errored false
          tags {
            defaultTags()
          }
        }
      }
    }
  }

  def "test span name update"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def result = builder.startSpan()

    expect:
    result.delegate.operationName == "some-name"

    when:
    result.updateName("other-name")

    then:
    result.delegate.operationName == "other-name"

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "other-name"
          resourceName "some-instrumentation"
        }
      }
    }
  }
}
