import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.context.ThreadLocalContextStorage
import org.skyscreamer.jsonassert.JSONAssert
import spock.lang.Subject

import java.security.InvalidParameterException

import static datadog.trace.api.DDTags.ERROR_MSG
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER
import static datadog.trace.instrumentation.opentelemetry14.trace.OtelConventions.SPAN_KIND_INTERNAL
import static io.opentelemetry.api.trace.SpanKind.CLIENT
import static io.opentelemetry.api.trace.SpanKind.CONSUMER
import static io.opentelemetry.api.trace.SpanKind.INTERNAL
import static io.opentelemetry.api.trace.SpanKind.PRODUCER
import static io.opentelemetry.api.trace.SpanKind.SERVER
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
          operationName "internal"
          resourceName "some-name"
        }
        span {
          childOfPrevious()
          operationName "internal"
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
          operationName "internal"
          resourceName "some-name"
        }
        span {
          childOfPrevious()
          operationName "internal"
          resourceName "other-name"
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
          operationName "internal"
          resourceName"some-name"
        }
      }
      trace(1) {
        span {
          parent()
          operationName "internal"
          resourceName"other-name"
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

  def "test simple span links"() {
    setup:
    def traceId = "1234567890abcdef1234567890abcdef" as String
    def spanId = "fedcba0987654321" as String
    def traceState = TraceState.builder().put("string-key", "string-value").build()

    def expectedLinksTag = """
    [
      { trace_id: "${traceId}",
        span_id: "${spanId}",
        flags: 1,
        tracestate: "string-key=string-value"}
    ]"""

    when:
    def span1 =tracer.spanBuilder("some-name")
      .addLink(SpanContext.getInvalid())  // Should not be added
      .addLink(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), traceState))
      .startSpan()
    span1.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
            tag("_dd.span_links", { JSONAssert.assertEquals(expectedLinksTag, it as String, true); return true })
          }
        }
      }
    }
  }

  def "test multiple span links"() {
    setup:
    def spanBuilder = tracer.spanBuilder("some-name")

    when:
    def links = []
    0..9.each {
      def traceId = "1234567890abcdef1234567890abcde$it" as String
      def spanId = "fedcba098765432$it" as String
      def traceState = TraceState.builder().put('string-key', 'string-value'+it).build()
      links << """{ trace_id: "${traceId}",
        span_id: "${spanId}",
        flags: 1,
        tracestate: "string-key=string-value$it"}"""
      spanBuilder.addLink(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), traceState))
    }
    def expectedLinksTag = "[${links.join(',')}]" as String

    spanBuilder.startSpan().end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
            tag("_dd.span_links", { JSONAssert.assertEquals(expectedLinksTag, it as String, true); return true })
          }
        }
      }
    }
  }

  def "test span link attributes"() {
    setup:
    def traceId = "1234567890abcdef1234567890abcdef" as String
    def spanId = "fedcba0987654321" as String
    def traceState = TraceState.builder().put("string-key", "string-value").build()

    def expectedLinksTag = """
    [
      { trace_id: "${traceId}",
        span_id: "${spanId}",
        flags: 1,
        tracestate: "string-key=string-value"
        ${ expectedAttributes == null ? "" : ", attributes: " + expectedAttributes }}
    ]"""

    when:
    def span1 =tracer.spanBuilder("some-name")
      .addLink(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), traceState), attributes)
      .startSpan()
    span1.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
            tag("_dd.span_links", { JSONAssert.assertEquals(expectedLinksTag, it as String, true); return true })
          }
        }
      }
    }

    where:
    attributes | expectedAttributes
    Attributes.empty() | null
    Attributes.builder().put("string-key", "string-value").put("long-key", 123456789L).put("double-key", 1234.5678D).put("boolean-key-true", true).put("boolean-key-false", false).build() | '{ string-key: "string-value", long-key: "123456789", double-key: "1234.5678", boolean-key-true: "true", boolean-key-false: "false" }'
    Attributes.builder().put("string-key-array", "string-value1", "string-value2", "string-value3").put("long-key-array", 123456L, 1234567L, 12345678L).put("double-key-array", 1234.5D, 1234.56D, 1234.567D).put("boolean-key-array", true, false, true).build() | '{ string-key-array.0: "string-value1", string-key-array.1: "string-value2", string-key-array.2: "string-value3", long-key-array.0: "123456", long-key-array.1: "1234567", long-key-array.2: "12345678", double-key-array.0: "1234.5", double-key-array.1: "1234.56", double-key-array.2: "1234.567", boolean-key-array.0: "true", boolean-key-array.1: "false", boolean-key-array.2: "true" }'
  }

  def "test span links trace state"() {
    setup:
    def traceId = "1234567890abcdef1234567890abcdef" as String
    def spanId = "fedcba0987654321" as String

    def expectedTraceStateJson = expectedTraceState == null ? '' : ", tracestate: \"$expectedTraceState\""
    def expectedLinksTag = """
    [
      { trace_id: "${traceId}",
        span_id: "${spanId}",
        flags: 1
        $expectedTraceStateJson
      }
    ]"""

    when:
    def span1 =tracer.spanBuilder("some-name")
      .addLink(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), traceState))
      .startSpan()
    span1.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
            tag("_dd.span_links", { JSONAssert.assertEquals(expectedLinksTag, it as String, true); return true })
          }
        }
      }
    }

    where:
    traceState                                                                                                                                 | expectedTraceState
    TraceState.getDefault()                                                                                                                    | null
    TraceState.builder().put("key", "value").build()                                                                                           | 'key=value'
    TraceState.builder().put("key1", "value1").put("key2", "value2").put("key3", "value3").put("key4", "value4").put("key5", "value5").build() | 'key5=value5,key4=value4,key3=value3,key2=value2,key1=value1'
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
          operationName "internal"
          if (tagSpan) {
            resourceName "other-resource"
          } else if (tagBuilder) {
            resourceName "some-resource"
          } else {
            resourceName "some-name"
          }
          errored false
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
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
    def result = tracer.spanBuilder("some-name")
      .setSpanKind(otelSpanKind)
      .startSpan()

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          tags {
            defaultTags()
            "$SPAN_KIND" "$tagSpanKind"
          }
        }
      }
    }

    where:
    otelSpanKind | tagSpanKind
    INTERNAL     | SPAN_KIND_INTERNAL
    SERVER       | SPAN_KIND_SERVER
    CLIENT       | SPAN_KIND_CLIENT
    PRODUCER     | SPAN_KIND_PRODUCER
    CONSUMER     | SPAN_KIND_CONSUMER
  }

  def "test span error status"() {
    setup:
    def result = tracer.spanBuilder("some-name").startSpan()

    when:
    result.setStatus(ERROR, "some-error")
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "internal"
          resourceName "some-name"
          errored true
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
            "$ERROR_MSG" "some-error"
          }
        }
      }
    }
  }

  def "test span status transition"() {
    setup:
    def result = tracer.spanBuilder("some-name").startSpan()

    when:
    result.setStatus(UNSET)

    then:
    !result.delegate.isError()
    result.delegate.getTag(ERROR_MSG) == null

    when:
    result.setStatus(ERROR, "some error")

    then:
    result.delegate.isError()
    result.delegate.getTag(ERROR_MSG) == "some error"

    when:
    result.setStatus(UNSET)

    then:
    result.delegate.isError()
    result.delegate.getTag(ERROR_MSG) == "some error"

    when:
    result.setStatus(OK)

    then:
    !result.delegate.isError()
    result.delegate.getTag(ERROR_MSG) == null

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "internal"
          resourceName "some-name"
          errored false
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
          }
        }
      }
    }
  }

  def "test span record exception"() {
    setup:
    def result = tracer.spanBuilder("some-name").startSpan()
    def message = "input can't be null"
    def exception = new InvalidParameterException(message)

    expect:
    result.delegate.getTag(ERROR_MSG) == null
    result.delegate.getTag(DDTags.ERROR_TYPE) == null
    result.delegate.getTag(DDTags.ERROR_STACK) == null
    !result.delegate.isError()

    when:
    result.recordException(exception)

    then:
    result.delegate.getTag(ERROR_MSG) == message
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
          operationName "internal"
          resourceName "some-name"
          errored false
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
            errorTags(exception)
          }
        }
      }
    }
  }

  def "test span name update"() {
    setup:
    def result = tracer.spanBuilder("some-name")
      .setSpanKind(SERVER)
      .startSpan()

    expect:
    result.delegate.operationName == SPAN_KIND_INTERNAL
    result.delegate.resourceName == "some-name"

    when:
    result.updateName("other-name")

    then:
    result.delegate.operationName == SPAN_KIND_INTERNAL
    result.delegate.resourceName == "other-name"

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "server.request"
          resourceName "other-name"
        }
      }
    }
  }

  def "test span update after end"() {
    setup:
    def result = tracer.spanBuilder("some-name").startSpan()

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
          operationName "internal"
          resourceName"some-name"
          errored true
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
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
