import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.core.propagation.PropagationTags

import static datadog.trace.api.TracePropagationStyle.NONE
import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*
import datadog.trace.context.TraceScope
import datadog.trace.core.propagation.ExtractedContext
import io.grpc.Context
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.trace.Span
import io.opentelemetry.trace.Status
import io.opentelemetry.trace.TracingContextUtils
import spock.lang.Subject

class OpenTelemetryTest extends InstrumentationSpecification {
  @Subject
  def tracer = OpenTelemetry.tracerProvider.get("test-inst")
  def httpPropagator = OpenTelemetry.getPropagators().httpTextFormat

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry-beta.enabled", "true")
  }

  def "test span tags"() {
    setup:
    def builder = tracer.spanBuilder("some name")
    if (tagBuilder) {
      builder.setAttribute(DDTags.RESOURCE_NAME, "some resource")
        .setAttribute("string", "a")
        .setAttribute("number", 1)
        .setAttribute("boolean", true)
    }
    def result = builder.startSpan()
    if (tagSpan) {
      result.setAttribute(DDTags.RESOURCE_NAME, "other resource")
      result.setAttribute("string", "b")
      result.setAttribute("number", 2)
      result.setAttribute("boolean", false)
    }

    expect:
    result instanceof MutableSpan
    (result as MutableSpan).localRootSpan == result.delegate
    tracer.currentSpan == null

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "test-inst"
          if (tagSpan) {
            resourceName "other resource"
          } else if (tagBuilder) {
            resourceName "some resource"
          } else {
            resourceName "some name"
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
          assert span.context().integrationName == "otel"
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

  def "test span exception"() {
    setup:
    def builder = tracer.spanBuilder("some name")
    def result = builder.startSpan()
    result.setStatus(Status.UNKNOWN)
    result.setAttribute(DDTags.ERROR_MSG, (String) exception.message)
    result.setAttribute(DDTags.ERROR_TYPE, (String) exception.class.name)
    final StringWriter errorString = new StringWriter()
    exception.printStackTrace(new PrintWriter(errorString))
    result.setAttribute(DDTags.ERROR_STACK, errorString.toString())

    expect:
    result instanceof MutableSpan
    (result as MutableSpan).localRootSpan == result.delegate
    (result as MutableSpan).isError() == (exception != null)
    tracer.currentSpan == null

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "test-inst"
          resourceName "some name"
          errored true
          tags {
            errorTags(exception.class)
            defaultTags()
          }
        }
      }
    }

    where:
    exception = new Exception()
  }

  def "test span links"() {
    setup:
    def builder = tracer.spanBuilder("some name")
    if (parentId) {
      def ctx = new ExtractedContext(DDTraceId.ONE, parentId, SAMPLER_DROP, null, PropagationTags.factory().empty(), NONE)
      builder.setParent(tracer.converter.toSpanContext(ctx))
    }
    if (linkId) {
      def ctx = new ExtractedContext(DDTraceId.ONE, linkId, SAMPLER_DROP, null, PropagationTags.factory().empty(), NONE)
      builder.addLink(tracer.converter.toSpanContext(ctx))
    }
    def result = builder.startSpan()

    expect:
    result instanceof MutableSpan
    (result as MutableSpan).localRootSpan == result.delegate
    tracer.currentSpan == null

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          if (expectedId) {
            traceDDId(DDTraceId.ONE)
            parentSpanId(expectedId)
          } else {
            parent()
          }
          operationName "test-inst"
          resourceName "some name"
          errored false
          tags {
            defaultTags(expectedId != null)
          }
        }
      }
    }

    where:
    parentId | linkId | expectedId
    null     | null   | null
    2        | null   | 2
    null     | 3      | 3
    2        | 3      | 2
  }

  def "test scope"() {
    setup:
    def span = tracer.spanBuilder("some name").startSpan()
    def scope = tracer.withSpan(span)

    expect:
    scope instanceof TraceScope
    tracer.currentSpan.delegate == scope.delegate.span()

    when:
    scope.close()

    then:
    tracer.currentSpan == null

    cleanup:
    span.end()
  }

  def "test closing scope when not on top"() {
    when:
    Span firstSpan = tracer.spanBuilder("someOperation").startSpan()
    Scope firstScope = tracer.withSpan(firstSpan)

    Span secondSpan = tracer.spanBuilder("someOperation").startSpan()
    Scope secondScope = tracer.withSpan(secondSpan)

    firstSpan.end()
    firstScope.close()

    then:
    tracer.currentSpan.delegate == secondScope.delegate.span()
    _ * TEST_PROFILING_CONTEXT_INTEGRATION._
    0 * _

    when:
    secondSpan.end()
    secondScope.close()

    then:
    tracer.currentSpan == null
    _ * TEST_PROFILING_CONTEXT_INTEGRATION._
    0 * _
  }

  def "test continuation"() {
    setup:
    def span = tracer.spanBuilder("some name").startSpan()
    TraceScope scope = tracer.withSpan(span)

    expect:
    tracer.currentSpan.delegate == span.delegate

    when:
    def continuation = scope.capture()

    then:
    continuation instanceof TraceScope.Continuation

    when:
    scope.close()

    then:
    tracer.currentSpan == null

    when:
    scope = continuation.activate()

    then:
    tracer.currentSpan.delegate == span.delegate

    cleanup:
    scope.close()
    span.end()
  }

  def "test inject extract"() {
    setup:
    def span = tracer.spanBuilder("some name").startSpan()
    def context = TracingContextUtils.withSpan(span, Context.current())
    def textMap = [:]

    when:
    span.delegate.samplingPriority = contextPriority
    httpPropagator.inject(context, textMap, new TextMapSetter())

    then:
    def expectedTraceparent = "00-${span.delegate.traceId.toHexStringPadded(32)}" +
      "-${DDSpanId.toHexStringPadded(span.delegate.spanId)}" +
      "-" + (propagatedPriority > 0 ? "01" : "00")
    def expectedTracestate = "dd=s:${propagatedPriority};p:${DDSpanId.toHexStringPadded(span.delegate.spanId)}"
    def expectedDatadogTags = null
    if (propagatedMechanism != UNKNOWN) {
      expectedDatadogTags = "_dd.p.dm=-" + propagatedMechanism
      expectedTracestate+= ";t.dm:-" + propagatedMechanism
    }
    def expectedTextMap = [
      "x-datadog-trace-id"         : "$span.delegate.traceId",
      "x-datadog-parent-id"        : "$span.delegate.spanId",
      "x-datadog-sampling-priority": propagatedPriority.toString(),
      "traceparent"                : expectedTraceparent,
      "tracestate"                 : expectedTracestate,
    ]
    if (expectedDatadogTags != null) {
      expectedTextMap.put("x-datadog-tags", expectedDatadogTags)
    }
    textMap == expectedTextMap

    when:
    def extractedContext = httpPropagator.extract(context, textMap, new TextMapGetter())
    def extract = TracingContextUtils.getSpanWithoutDefault(extractedContext)

    then:
    extract.context.traceId == span.context.traceId
    extract.context.spanId == span.context.spanId
    extract.context.delegate.samplingPriority == propagatedPriority

    cleanup:
    span.end()

    where:
    contextPriority | propagatedPriority | propagatedMechanism
    SAMPLER_DROP    | SAMPLER_DROP       | UNKNOWN
    SAMPLER_KEEP    | SAMPLER_KEEP       | UNKNOWN
    UNSET           | SAMPLER_KEEP       | AGENT_RATE
    USER_KEEP       | USER_KEEP          | UNKNOWN
    USER_DROP       | USER_DROP          | UNKNOWN
  }

  def "tolerate null span activation"() {
    when:
    try {
      tracer.withSpan(null)?.close()
    } catch (Exception ignored) {}

    // make sure scope stack has been left in a valid state
    def testSpan = tracer.spanBuilder("some name").startSpan()
    def testScope = tracer.withSpan(testSpan)
    testSpan.end()
    testScope.close()

    then:
    tracer.currentSpan == null
  }

  static class TextMapGetter implements HttpTextFormat.Getter<Map<String, String>> {
    @Override
    String get(Map<String, String> carrier, String key) {
      return carrier.get(key)
    }
  }

  static class TextMapSetter implements HttpTextFormat.Setter<Map<String, String>> {
    @Override
    void set(Map<String, String> carrier, String key, String value) {
      carrier.put(key, value)
    }
  }
}
