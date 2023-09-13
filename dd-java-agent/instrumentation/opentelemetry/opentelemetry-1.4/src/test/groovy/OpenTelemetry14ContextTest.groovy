import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.instrumentation.opentelemetry14.OtelContext
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import spock.lang.Subject

import javax.annotation.Nullable

import static datadog.trace.bootstrap.instrumentation.api.ScopeSource.MANUAL

class OpenTelemetry14ContextTest extends AgentTestRunner {
  @Subject
  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("context-instrumentation")

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry.experimental.enabled", "true")
  }

  def "test Span.current/makeCurrent()"() {
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

  def "test Context.makeCurrent() to activate a span without prior active span"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def otelSpan = builder.startSpan()

    when:
    def currentSpan = Span.current()

    then:
    currentSpan != null
    !currentSpan.spanContext.isValid()

    when:
    def contextWithSpan = Context.current().with(otelSpan)
    def scope = contextWithSpan.makeCurrent()
    currentSpan = Span.current()

    then:
    currentSpan == otelSpan

    when:
    scope.close()
    currentSpan = Span.current()

    then:
    currentSpan != null
    !currentSpan.spanContext.isValid()

    cleanup:
    otelSpan.end()
  }

  def "test Context.makeCurrent() to activate a span with another currently active span"() {
    setup:
    def ddSpan = TEST_TRACER.startSpan("dd-api", "some-name")
    def ddScope = TEST_TRACER.activateSpan(ddSpan, MANUAL)
    def builder = tracer.spanBuilder("other-name")
    def otelSpan = builder.startSpan()

    when:
    def currentSpan = Span.current()

    then:
    currentSpan != null
    currentSpan.spanContext.traceId == ddSpan.traceId.toHexStringPadded(32)
    currentSpan.spanContext.spanId == DDSpanId.toHexStringPadded(ddSpan.spanId)

    when:
    def contextWithSpan = Context.current().with(otelSpan)
    def scope = contextWithSpan.makeCurrent()
    currentSpan = Span.current()

    then:
    currentSpan == otelSpan

    when:
    scope.close()
    currentSpan = Span.current()

    then:
    currentSpan != null
    currentSpan.spanContext.traceId == ddSpan.traceId.toHexStringPadded(32)
    currentSpan.spanContext.spanId == DDSpanId.toHexStringPadded(ddSpan.spanId)

    cleanup:
    otelSpan.end()
    ddScope.close()
    ddSpan.finish()
  }

  def "test Context.makeCurrent() to activate an already active span"() {
    when:
    def ddSpan = TEST_TRACER.startSpan("dd-api", "some-name")
    def ddScope = TEST_TRACER.activateSpan(ddSpan, MANUAL)
    def currentSpan = Span.current()

    then:
    currentSpan != null
    currentSpan.spanContext.traceId == ddSpan.traceId.toHexStringPadded(32)
    currentSpan.spanContext.spanId == DDSpanId.toHexStringPadded(ddSpan.spanId)

    when:
    def contextWithSpan = Context.current().with(currentSpan)
    def scope = contextWithSpan.makeCurrent()
    currentSpan = Span.current()

    then:
    currentSpan != null
    currentSpan.spanContext.traceId == ddSpan.traceId.toHexStringPadded(32)
    currentSpan.spanContext.spanId == DDSpanId.toHexStringPadded(ddSpan.spanId)

    when:
    scope.close()
    currentSpan = Span.current()

    then:
    currentSpan != null
    currentSpan.spanContext.traceId == ddSpan.traceId.toHexStringPadded(32)
    currentSpan.spanContext.spanId == DDSpanId.toHexStringPadded(ddSpan.spanId)

    when:
    ddScope.close()
    ddSpan.finish()
    currentSpan = Span.current()

    then:
    currentSpan != null
    !currentSpan.spanContext.isValid()
  }

  def "test setting non-Datadog context"() {
    when:
    def rootScope = Context.root().makeCurrent()
    then:
    Context.current() == Context.root()
    cleanup:
    rootScope.close()
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
