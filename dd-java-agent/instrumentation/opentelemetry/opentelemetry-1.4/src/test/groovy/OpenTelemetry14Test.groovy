import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.context.ThreadLocalContextStorage
import spock.lang.Subject

import java.security.InvalidParameterException

import static io.opentelemetry.api.trace.StatusCode.ERROR
import static io.opentelemetry.api.trace.StatusCode.OK
import static io.opentelemetry.api.trace.StatusCode.UNSET

class OpenTelemetry14Test extends AgentTestRunner {
  @Subject
  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("some-instrumentation")

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry.experimental.enabled", "true")
  }

  def "test parent span using active span"() {
    setup:
    def parentSpan = tracer.spanBuilder("some-name").startSpan()
    def scope = parentSpan.makeCurrent()

    when:
    def childSpan = tracer.spanBuilder("other-name").startSpan()
    childSpan.end()
    scope.close()
    parentSpan.end()

    then:
    assertTraces(1) {
      trace(2) {
        span {
          parent()
          operationName "some-name"
          resourceName "some-name"
        }
        span {
          childOfPrevious()
          operationName "other-name"
          resourceName "other-name"
        }
      }
    }
  }

  def "test parent span using reference"() {
    setup:
    def parentSpan = tracer.spanBuilder("some-name").startSpan()

    when:
    def childSpan = tracer.spanBuilder("other-name")
      .setParent(Context.current().with(parentSpan))
      .startSpan()
    childSpan.end()
    parentSpan.end()

    then:
    assertTraces(1) {
      trace(2) {
        span {
          parent()
          operationName "some-name"
        }
        span {
          childOfPrevious()
          operationName "other-name"
        }
      }
    }
  }

  def "test parent span using invalid reference"() {
    when:
    def invalidCurrentSpanContext = Context.root() // Contains a SpanContext with TID/SID to 0 as current span
    def childSpan = tracer.spanBuilder("some-name")
      .setParent(invalidCurrentSpanContext)
      .startSpan()
    childSpan.end()

    TEST_WRITER.waitForTraces(1)
    def trace = TEST_WRITER.firstTrace()


    then:
    trace.size() == 1
    trace[0].spanId != 0
  }

  def "test no parent to create new root span"() {
    setup:
    def parentSpan = tracer.spanBuilder("some-name").startSpan()
    def scope = parentSpan.makeCurrent()

    when:
    def childSpan = tracer.spanBuilder("other-name")
      .setNoParent()
      .startSpan()
    childSpan.end()
    scope.close()
    parentSpan.end()

    then:
    assertTraces(2) {
      trace(1) {
        span {
          parent()
          operationName "some-name"
        }
      }
      trace(1) {
        span {
          parent()
          operationName "other-name"
        }
      }
    }
  }

  def "test non-supported features do not crash"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def anotherSpan = tracer.spanBuilder("another-name").startSpan()
    anotherSpan.end()

    when:
    // Adding link is not supported
    builder.addLink(anotherSpan.getSpanContext())
    // Adding event is not supported
    def result = builder.startSpan()
    result.addEvent("some-event")
    result.end()

    then:
    assertTraces(2) {
      trace(1) {
        span {}
      }
      trace(1) {
        span {}
      }
    }
  }

  def "test span attributes"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    if (tagBuilder) {
      builder.setAttribute(DDTags.RESOURCE_NAME, "some-resource")
        .setAttribute("string", "a")
        .setAttribute("null-string", null)
        .setAttribute("empty_string", "")
        .setAttribute("number", 1)
        .setAttribute("boolean", true)
        .setAttribute(AttributeKey.stringKey("null-string-attribute"), null)
        .setAttribute(AttributeKey.stringKey("empty-string-attribute"), "")
        .setAttribute(AttributeKey.stringArrayKey("string-array"), ["a", "b", "c"])
        .setAttribute(AttributeKey.booleanArrayKey("boolean-array"), [true, false])
        .setAttribute(AttributeKey.longArrayKey("long-array"), [1L, 2L, 3L, 4L])
        .setAttribute(AttributeKey.doubleArrayKey("double-array"), [1.23D, 4.56D])
        .setAttribute(AttributeKey.stringArrayKey("empty-array"), Collections.emptyList())
        .setAttribute(AttributeKey.stringArrayKey("null-array"), null)
    }
    def result = builder.startSpan()
    if (tagSpan) {
      result.setAttribute(DDTags.RESOURCE_NAME, "other-resource")
      result.setAttribute("string", "b")
      result.setAttribute("empty_string", "")
      result.setAttribute("number", 2)
      result.setAttribute("boolean", false)
      result.setAttribute(AttributeKey.stringKey("null-string-attribute"), null)
      result.setAttribute(AttributeKey.stringKey("empty-string-attribute"), "")
      result.setAttribute(AttributeKey.stringArrayKey("string-array"), ["d", "e", "f"])
      result.setAttribute(AttributeKey.booleanArrayKey("boolean-array"), [false, true])
      result.setAttribute(AttributeKey.longArrayKey("long-array"), [5L, 6L, 7L, 8L])
      result.setAttribute(AttributeKey.doubleArrayKey("double-array"), [2.34D, 5.67D])
      result.setAttribute(AttributeKey.stringArrayKey("empty-array"), Collections.emptyList())
      result.setAttribute(AttributeKey.stringArrayKey("null-array"), null)
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
            resourceName "some-name"
          }
          errored false
          tags {
            if (tagSpan) {
              "string" "b"
              "empty_string" ""
              "number" 2
              "boolean" false
              "empty-string-attribute" ""
              "string-array.0" "d"
              "string-array.1" "e"
              "string-array.2" "f"
              "boolean-array.0" false
              "boolean-array.1" true
              "long-array.0" 5L
              "long-array.1" 6L
              "long-array.2" 7L
              "long-array.3" 8L
              "double-array.0" 2.34D
              "double-array.1" 5.67D
              "empty-array" ""
            } else if (tagBuilder) {
              "string" "a"
              "empty_string" ""
              "number" 1
              "boolean" true
              "empty-string-attribute" ""
              "string-array.0" "a"
              "string-array.1" "b"
              "string-array.2" "c"
              "boolean-array.0" true
              "boolean-array.1" false
              "long-array.0" 1L
              "long-array.1" 2L
              "long-array.2" 3L
              "long-array.3" 4L
              "double-array.0" 1.23D
              "double-array.1" 4.56D
              "empty-array" ""
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

  def "test span kinds"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    builder.setSpanKind(otelSpanKind)
    def result = builder.startSpan()

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          spanType(tagSpanKind)
        }
      }
    }

    where:
    otelSpanKind | tagSpanKind
    SpanKind.CLIENT | Tags.SPAN_KIND_CLIENT
    SpanKind.CONSUMER | Tags.SPAN_KIND_CONSUMER
    SpanKind.INTERNAL | "internal"
    SpanKind.PRODUCER | Tags.SPAN_KIND_PRODUCER
    SpanKind.SERVER | Tags.SPAN_KIND_SERVER
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
          resourceName "some-name"
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
    result.setStatus(ERROR, "some error")

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
          resourceName "some-name"
          errored false
          tags {
            defaultTags()
          }
        }
      }
    }
  }

  def "test span record exception"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def result = builder.startSpan()
    def message = "input can't be null"
    def exception = new InvalidParameterException(message)

    expect:
    result.delegate.getTag(DDTags.ERROR_MSG) == null
    result.delegate.getTag(DDTags.ERROR_TYPE) == null
    result.delegate.getTag(DDTags.ERROR_STACK) == null
    !result.delegate.isError()

    when:
    result.recordException(exception)

    then:
    result.delegate.getTag(DDTags.ERROR_MSG) == message
    result.delegate.getTag(DDTags.ERROR_TYPE) == InvalidParameterException.name
    result.delegate.getTag(DDTags.ERROR_STACK) != null
    !result.delegate.isError()

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "some-name"
          resourceName "some-name"
          errored false
          tags {
            defaultTags()
            errorTags(exception)
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
          resourceName "some-name"
        }
      }
    }
  }

  def "test span update after end"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def result = builder.startSpan()

    when:
    result.setAttribute("string", "value")
    result.setStatus(ERROR)
    result.end()
    result.updateName("other-name")
    result.setAttribute("string", "other-value")
    result.setStatus(OK)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "some-name"
          errored true
          tags {
            defaultTags()
            "string" "value"
          }
        }
      }
    }
  }

  @Override
  void cleanup() {
    // Test for context leak
    assert Context.current() == Context.root()
    // Safely reset OTel context storage
    ThreadLocalContextStorage.THREAD_LOCAL_STORAGE.remove()
  }
}
