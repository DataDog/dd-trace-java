import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.opentelemetry14.OtelContext
import datadog.trace.instrumentation.opentelemetry14.OtelSpan
import datadog.trace.instrumentation.opentelemetry14.OtelSpanBuilder
import datadog.trace.instrumentation.opentelemetry14.OtelTracer
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import spock.lang.Subject

import javax.annotation.Nullable

import static datadog.trace.bootstrap.instrumentation.api.ScopeSource.MANUAL
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

  def "test injection"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def result = builder.startSpan()

    expect:
    tracer instanceof OtelTracer
    builder instanceof OtelSpanBuilder
    result instanceof OtelSpan
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
        }
        span {
          childOfPrevious()
          operationName "other-name"
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
        .setAttribute("empty_string", "")
        .setAttribute("number", 1)
        .setAttribute("boolean", true)
    }
    def result = builder.startSpan()
    if (tagSpan) {
      result.setAttribute(DDTags.RESOURCE_NAME, "other-resource")
      result.setAttribute("string", "b")
      result.setAttribute("empty_string", "")
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
            resourceName "some-name"
          }
          errored false
          tags {
            if (tagSpan) {
              "string" "b"
              "empty_string" ""
              "number" 2
              "boolean" false
            } else if (tagBuilder) {
              "string" "a"
              "empty_string" ""
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
          resourceName "some-name"
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
          resourceName "some-name"
        }
      }
    }
  }

  def "test span current/makeCurrent"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def otelSpan = builder.startSpan()

    when:
    def currentSpan = Span.current()

    then:
    currentSpan != null
    currentSpan.spanContext.traceId == "00000000000000000000000000000000"
    currentSpan.spanContext.spanId == "0000000000000000"

    when:
    def scope = otelSpan.makeCurrent()
    currentSpan = Span.current()

    then:
    currentSpan == otelSpan

    when:
    def ddSpan = TEST_TRACER.startSpan("dd-api", "other-name")
    def ddScope = TEST_TRACER.activateSpan(ddSpan, MANUAL)
    currentSpan = Span.current()

    then:
    currentSpan.spanContext.traceId == ddSpan.traceId.toHexString()
    currentSpan.spanContext.spanId == DDSpanId.toHexStringPadded(ddSpan.spanId)

    cleanup:
    ddScope.close()
    ddSpan.finish()
    scope.close()
    otelSpan.end()
  }

  def "test mixing manual and OTel instrumentation"() {
    setup:
    def otelParentSpan = tracer.spanBuilder("some-name").startSpan()

    when:
    def otelParentScope = otelParentSpan.makeCurrent()
    def activeSpan = TEST_TRACER.activeSpan()

    then:
    activeSpan.operationName == "some-name"
    DDSpanId.toHexStringPadded(activeSpan.spanId) == otelParentSpan.getSpanContext().spanId

    when:
    def ddChildSpan = TEST_TRACER.startSpan("dd-api", "other-name")
    def ddChildScope = TEST_TRACER.activateSpan(ddChildSpan, MANUAL)
    def current = Span.current()

    then:
    DDSpanId.toHexStringPadded(ddChildSpan.spanId) == current.getSpanContext().spanId

    when:
    def otelGrandChildSpan = tracer.spanBuilder("another-name").startSpan()
    def otelGrandChildScope= otelGrandChildSpan.makeCurrent()
    activeSpan = TEST_TRACER.activeSpan()

    then:
    activeSpan.operationName == "another-name"
    DDSpanId.toHexStringPadded(activeSpan.spanId) == otelGrandChildSpan.getSpanContext().spanId

    when:
    otelGrandChildScope.close()
    otelGrandChildSpan.end()
    ddChildScope.close()
    ddChildSpan.finish()
    otelParentScope.close()
    otelParentSpan.end()

    then:
    assertTraces(1) {
      trace(3) {
        span {
          parent()
          operationName "some-name"
        }
        span {
          childOfPrevious()
          operationName "other-name"
        }
        span {
          childOfPrevious()
          operationName "another-name"
        }
      }
    }
  }

  def "test context spans retrieval"() {
    setup:
    def parentSpan = tracer.spanBuilder("some-name").startSpan()
    def parentScope = parentSpan.makeCurrent()
    def currentSpanKey = ContextKey.named(OtelContext.OTEL_CONTEXT_SPAN_KEY)
    def rootSpanKey = ContextKey.named(OtelContext.DATADOG_CONTEXT_ROOT_SPAN_KEY)

    when:
    def current = Context.current()

    then:
    current.get(currentSpanKey) == parentSpan
    current.get(rootSpanKey) == parentSpan

    when:
    def childSpan = tracer.spanBuilder("other-name").startSpan()
    def childScope = childSpan.makeCurrent()
    current = Context.current()

    then:
    current.get(currentSpanKey) == childSpan
    current.get(rootSpanKey) == parentSpan

    when:
    childScope.close()
    childSpan.end()
    current = Context.current()

    then:
    current.get(currentSpanKey) == parentSpan
    current.get(rootSpanKey) == parentSpan

    cleanup:
    parentScope.close()
    parentSpan.end()
  }

  def "test context extraction and injection"() {
    setup:
    def propagator = W3CTraceContextPropagator.getInstance()
    def httpHeaders = ['traceparent': traceparent]
    def context = propagator.extract(Context.root(), httpHeaders, new TextMapGetter<Map<String, String>>() {
        @Override
        Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet()
        }

        @Override
        String get(@Nullable Map<String, String> carrier, String key) {
          return carrier.get(key)
        }
      })

    def localSpan = tracer.spanBuilder("some-name")
      .setParent(context)
      .startSpan()

    when:
    def localSpanContext = localSpan.getSpanContext()
    def localSpanId = localSpanContext.getSpanId()
    def spanSampled = localSpanContext.getTraceFlags().isSampled()
    def scope = localSpan.makeCurrent()
    Map<String, String> injectedHeaders = [:]
    propagator.inject(Context.current(), injectedHeaders, new TextMapSetter<Map<String, String>>() {
        @Override
        void set(@Nullable Map<String, String> carrier, String key, String value) {
          carrier.put(key, value)
        }
      })
    scope.close()
    localSpan.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "some-name"
          traceDDId(DDTraceId.fromHex(traceId))
          parentSpanId(DDSpanId.fromHex(spanId).toLong() as BigInteger)
        }
      }
    }
    spanSampled == sampled
    injectedHeaders == ['traceparent': "00-$traceId-$localSpanId-$sampleFlag" as String]

    where:
    traceId | spanId | sampled
    '00000000000000001111111111111111' | '2222222222222222' | true
    '00000000000000001111111111111111' | '2222222222222222' | false
    sampleFlag = sampled ? '01' : '00'
    traceparent = "00-$traceId-$spanId-$sampleFlag" as String
  }
}
