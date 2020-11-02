import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDId
import datadog.trace.api.DDTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.instrumentation.opentelemetry.OtelContextPropagators
import datadog.trace.instrumentation.opentelemetry.OtelTracer
import datadog.trace.instrumentation.opentelemetry.TypeConverter
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.PropagatedSpan
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapPropagator
import spock.lang.Ignore
import spock.lang.Subject

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class OpenTelemetryTest extends AgentTestRunner {
  @Subject
  def tracer = OpenTelemetry.get().getTracerProvider().get("test-inst")
  def propagator = OpenTelemetry.getGlobalPropagators().textMapPropagator

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry-beta.enabled", "true")
  }

  def setup() {
    assert tracer instanceof OtelTracer
    assert propagator instanceof OtelContextPropagators.OtelTextMapPropagator
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
    Span.current() == Span.getInvalid()

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
          metrics {
            defaultMetrics()
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

  def "test span exception"() {
    setup:
    def result = tracer.spanBuilder("some name").startSpan()
    if (attributes) {
      result.recordException(exception, attributes)
    } else {
      result.recordException(exception)
    }

    expect:
    result.delegate.isError()
    !Span.current().spanContext.isValid()

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
            if (attributes) {
              "foo" "bar"
            }
            defaultTags()
          }
          metrics {
            defaultMetrics()
          }
        }
      }
    }

    where:
    exception = new Exception()
    attributes << [null, Attributes.builder().put("foo", "bar").build()]
  }

  def "test span links"() {
    setup:
    TypeConverter converter = tracer.converter
    def builder = tracer.spanBuilder("some name")
    if (parentId) {
      def spanContext = converter.toSpanContext(new ExtractedContext(DDId.ONE, DDId.from(parentId), 0, null, [:], [:]))
      def parent = PropagatedSpan.create(spanContext)
      assert parent.spanContext.remote
      def ctx = parent.storeInContext(Context.root())
      builder.setParent(ctx)
    }
    if (linkId) {
      def spanContext = converter.toSpanContext(new ExtractedContext(DDId.ONE, DDId.from(linkId), 0, null, [:], [:]))
      builder.addLink(spanContext)
    }
    def result = builder.startSpan()

    expect:
    !result.spanContext.remote
    !Span.current().spanContext.isValid()

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          if (expectedId) {
            traceDDId(DDId.ONE)
            parentDDId(DDId.from(expectedId))
          } else {
            parent()
          }
          operationName "test-inst"
          resourceName "some name"
          errored false
          tags {
            defaultTags(expectedId != null)
          }
          metrics {
            defaultMetrics()
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

  def "test independent SpanContext"() {
    setup:
    def builder = tracer.spanBuilder("some name")
    def spanContext = SpanContext.create("00000000000000000000000000000001", "0000000000000001", TraceFlags.default, TraceState.default)
    builder.addLink(spanContext)
    def result = builder.startSpan()

    expect:
    spanContext.isValid()
    !result.spanContext.remote
    !Span.current().spanContext.isValid()

    when:
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          traceDDId(DDId.ONE)
          parentDDId(DDId.ONE)
          operationName "test-inst"
          resourceName "some name"
          errored false
          tags {
            defaultTags(true)
          }
          metrics {
            defaultMetrics()
          }
        }
      }
    }
  }

  def "test scope"() {
    setup:
    def span = tracer.spanBuilder("some name").startSpan()
    def scope = span.storeInContext(Context.current()).makeCurrent()

    expect:
    Span.current().delegate == activeSpan()

    when:
    def child = tracer.spanBuilder("some name").startSpan()

    then:
    child.delegate.traceId == span.delegate.traceId
    child.delegate.parentId == span.delegate.spanId

    when:
    scope.close()

    then:
    !Span.current().spanContext.isValid()

    cleanup:
    child.end()
    span.end()
  }

  def "test closing scope when not on top"() {
    when:
    Span firstSpan = tracer.spanBuilder("someOperation").startSpan()
    Scope firstScope = firstSpan.storeInContext(Context.current()).makeCurrent()

    Span secondSpan = tracer.spanBuilder("someOperation").startSpan()
    Scope secondScope = secondSpan.storeInContext(Context.current()).makeCurrent()

    firstSpan.end()
    firstScope.close()

    // OpenTelemetry will log a warning and continue closing the first scope resulting in an empty context.

    then:
    !Span.current().spanContext.valid

    // This results in our context being out of sync.
    secondSpan.delegate == activeSpan()

    1 * STATS_D_CLIENT.incrementCounter("scope.close.error")
    0 * _

    when:
    secondSpan.end()
    secondScope.close()

    // Closing the scopes out of order results in the previous context being restored.

    then:
    Span.current().delegate == firstSpan.delegate

    // This results in our context being out of sync.
    activeSpan() == null
    0 * _
  }

  @Ignore
  def "test continuation"() {
    // continuations are currently not supported with OTel.
    throw new UnsupportedOperationException()
  }

  def "test inject extract"() {
    setup:
    def span = tracer.spanBuilder("some name").startSpan()
    def context = span.storeInContext(Context.current())
    Map<String, String> textMap = [:]

    when:
    Span.fromContext(context).spanContext.isValid()
    span.delegate.samplingPriority = contextPriority
    propagator.inject(context, textMap, new TextMapSetter())

    then:
    textMap == [
      "x-datadog-trace-id"         : "$span.delegate.traceId",
      "x-datadog-parent-id"        : "$span.delegate.spanId",
      "x-datadog-sampling-priority": propagatedPriority.toString(),
    ]

    when:
    def extractedContext = propagator.extract(context, textMap, new TextMapGetter())
    def extract = Span.fromContext(extractedContext)

    then:
    extract.spanContext.remote
    extract.spanContext.traceIdAsHexString == span.spanContext.traceIdAsHexString
    extract.spanContext.spanIdAsHexString == span.spanContext.spanIdAsHexString

    cleanup:
    span.end()

    where:
    contextPriority               | propagatedPriority
    PrioritySampling.SAMPLER_DROP | PrioritySampling.SAMPLER_DROP
    PrioritySampling.SAMPLER_KEEP | PrioritySampling.SAMPLER_KEEP
    PrioritySampling.UNSET        | PrioritySampling.SAMPLER_KEEP
    PrioritySampling.USER_KEEP    | PrioritySampling.USER_KEEP
    PrioritySampling.USER_DROP    | PrioritySampling.USER_DROP
  }

  static class TextMapGetter implements TextMapPropagator.Getter<Map<String, String>> {
    @Override
    Iterable<String> keys(Map<String, String> carrier) {
      return carrier.keySet()
    }

    @Override
    String get(Map<String, String> carrier, String key) {
      return carrier.get(key)
    }
  }

  static class TextMapSetter implements TextMapPropagator.Setter<Map<String, String>> {
    @Override
    void set(Map<String, String> carrier, String key, String value) {
      carrier.put(key, value)
    }
  }
}
