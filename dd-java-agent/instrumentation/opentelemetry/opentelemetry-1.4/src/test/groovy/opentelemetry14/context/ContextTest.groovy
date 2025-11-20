package opentelemetry14.context

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanId
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.context.ImplicitContextKeyed
import io.opentelemetry.context.ThreadLocalContextStorage
import spock.lang.Subject

import static datadog.opentelemetry.shim.context.OtelContext.OTEL_CONTEXT_ROOT_SPAN_KEY
import static datadog.opentelemetry.shim.context.OtelContext.OTEL_CONTEXT_SPAN_KEY
import static datadog.opentelemetry.shim.trace.OtelConventions.SPAN_KIND_INTERNAL

class ContextTest extends InstrumentationSpecification {
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
    def currentSpanFromContext = Span.fromContext(Context.current())
    def currentSpanFromContextOrNull = Span.fromContextOrNull(Context.current())

    then: "current span must be invalid or null"
    currentSpan != null
    !currentSpan.spanContext.valid
    currentSpanFromContext != null
    !currentSpanFromContext.spanContext.valid
    currentSpanFromContextOrNull == null

    when:
    def scope = otelSpan.makeCurrent()
    currentSpan = Span.current()
    currentSpanFromContext = Span.fromContext(Context.current())
    currentSpanFromContextOrNull = Span.fromContextOrNull(Context.current())

    then: "OTel span must be current span"
    currentSpan == otelSpan
    currentSpanFromContext == otelSpan
    currentSpanFromContextOrNull == otelSpan

    when:
    def ddSpan = TEST_TRACER.startSpan("dd-api", "other-name")
    def ddScope = TEST_TRACER.activateManualSpan(ddSpan)
    currentSpan = Span.current()

    then: "Datadog span must be current span"
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
    def ddScope = TEST_TRACER.activateManualSpan(ddSpan)
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
    def ddScope = TEST_TRACER.activateManualSpan(ddSpan)
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

    cleanup:
    ddScope.close()
    ddSpan.finish()
  }

  def "test clearing context"() {
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
    activeSpan.operationName == SPAN_KIND_INTERNAL
    activeSpan.resourceName == "some-name"
    DDSpanId.toHexStringPadded(activeSpan.spanId) == otelParentSpan.getSpanContext().spanId

    when:
    def ddChildSpan = TEST_TRACER.startSpan("dd-api", "other-name")
    def ddChildScope = TEST_TRACER.activateManualSpan(ddChildSpan)
    def current = Span.current()

    then:
    DDSpanId.toHexStringPadded(ddChildSpan.spanId) == current.getSpanContext().spanId

    when:
    def otelGrandChildSpan = tracer.spanBuilder("another-name").startSpan()
    def otelGrandChildScope= otelGrandChildSpan.makeCurrent()
    activeSpan = TEST_TRACER.activeSpan()

    then:
    activeSpan.operationName == SPAN_KIND_INTERNAL
    activeSpan.resourceName == "another-name"
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
          operationName "internal"
          resourceName "some-name"
        }
        span {
          childOfPrevious()
          operationName "other-name"
        }
        span {
          childOfPrevious()
          operationName "internal"
          resourceName "another-name"
        }
      }
    }

    cleanup:
    otelGrandChildScope?.close()
    otelGrandChildSpan?.end()
    ddChildScope?.close()
    ddChildSpan?.finish()
    otelParentScope.close()
    otelParentSpan.end()
  }

  def "test context spans retrieval"() {
    setup:
    def parentSpan = tracer.spanBuilder("some-name").startSpan()
    def parentScope = parentSpan.makeCurrent()
    def currentSpanKey = ContextKey.named(OTEL_CONTEXT_SPAN_KEY)
    def rootSpanKey = ContextKey.named(OTEL_CONTEXT_ROOT_SPAN_KEY)

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

  def "test custom object storage"() {
    setup:
    def context = Context.root()
    def originalContext = context
    def data1 = new CustomData()
    def data2 = new CustomData()

    when:
    context = context.with(data1)

    then:
    CustomData.fromContext(context) == data1
    CustomData.fromContext(originalContext) == null

    when:
    context = context.with(data2)

    then:
    CustomData.fromContext(context) == data2

    when:
    context = context.with(CustomData.KEY, null)

    then:
    context.get(CustomData.KEY) == null
  }

  def "test Baggage.current/makeCurrent()"() {
    when:
    def otelBaggage = Baggage.current()
    def otelBaggageFromContext = Baggage.fromContext(Context.current())
    def otelBaggageFromContextOrNull = Baggage.fromContextOrNull(Context.current())

    then: "current baggage must be empty or null"
    otelBaggage != null
    otelBaggage.isEmpty()
    otelBaggageFromContext != null
    otelBaggageFromContext.isEmpty()
    otelBaggageFromContextOrNull == null

    when:
    def otelScope = Baggage.builder()
      .put("foo", "otel_value_to_be_replaced")
      .put("FOO","OTEL_UNTOUCHED")
      .put("remove_me_key", "otel_remove_me_value")
      .build()
      .makeCurrent()
    otelBaggage = Baggage.current()
    otelBaggageFromContext = Baggage.fromContext(Context.current())
    otelBaggageFromContextOrNull = Baggage.fromContextOrNull(Context.current())

    then: "OTel baggage must be current"
    otelBaggage != null
    otelBaggage.size() == 3
    otelBaggage.getEntryValue("foo") == "otel_value_to_be_replaced"
    otelBaggage.getEntryValue("FOO") == "OTEL_UNTOUCHED"
    otelBaggage.getEntryValue("remove_me_key") == "otel_remove_me_value"
    otelBaggage.asMap() == otelBaggageFromContext.asMap()
    otelBaggage.asMap() == otelBaggageFromContextOrNull.asMap()

    when:
    def ddContext = datadog.context.Context.current()
    def ddBaggage = datadog.trace.bootstrap.instrumentation.api.Baggage.fromContext(ddContext)
    ddBaggage.addItem("new_foo", "dd_new_value")
    ddBaggage.addItem("foo", "dd_overwrite_value")
    ddBaggage.removeItem("remove_me_key")
    def ddScope = ddContext.with(ddBaggage).attach()
    otelBaggage = Baggage.current()
    otelBaggageFromContext = Baggage.fromContext(Context.current())
    otelBaggageFromContextOrNull = Baggage.fromContextOrNull(Context.current())

    then: "baggage must contain Datadog changes"
    otelBaggage != null
    otelBaggage.size() == 3
    otelBaggage.getEntryValue("foo") == "dd_overwrite_value"
    otelBaggage.getEntryValue("FOO") == "OTEL_UNTOUCHED"
    otelBaggage.getEntryValue("new_foo") == "dd_new_value"
    otelBaggage.asMap() == otelBaggageFromContext.asMap()
    otelBaggage.asMap() == otelBaggageFromContextOrNull.asMap()

    when:
    ddScope.close()
    otelScope.close()

    then: "current baggage must be empty or null"
    Baggage.current().isEmpty()

    when:
    ddContext = datadog.context.Context.current()
    ddBaggage = datadog.trace.bootstrap.instrumentation.api.Baggage.create([
      "foo"           : "dd_value_to_be_replaced",
      "FOO"           : "DD_UNTOUCHED",
      "remove_me_key" : "dd_remove_me_value"
    ])
    ddScope = ddContext.with(ddBaggage).attach()
    otelBaggage = Baggage.current()
    otelBaggageFromContext = Baggage.fromContext(Context.current())
    otelBaggageFromContextOrNull = Baggage.fromContextOrNull(Context.current())

    then: "Datadog baggage must be current"
    otelBaggage != null
    otelBaggage.size() == 3
    otelBaggage.getEntryValue("foo") == "dd_value_to_be_replaced"
    otelBaggage.getEntryValue("FOO") == "DD_UNTOUCHED"
    otelBaggage.getEntryValue("remove_me_key") == "dd_remove_me_value"
    otelBaggage.asMap() == otelBaggageFromContext.asMap()
    otelBaggage.asMap() == otelBaggageFromContextOrNull.asMap()

    when:
    def builder = otelBaggage.toBuilder()
    builder.put("new_foo", "otel_new_value")
    builder.put("foo", "otel_overwrite_value")
    builder.remove("remove_me_key")
    otelScope = builder.build().makeCurrent()
    otelBaggage = Baggage.current()
    otelBaggageFromContext = Baggage.fromContext(Context.current())
    otelBaggageFromContextOrNull = Baggage.fromContextOrNull(Context.current())

    then: "baggage must contain OTel changes"
    otelBaggage != null
    otelBaggage.size() == 3
    otelBaggage.getEntryValue("foo") == "otel_overwrite_value"
    otelBaggage.getEntryValue("FOO") == "DD_UNTOUCHED"
    otelBaggage.getEntryValue("new_foo") == "otel_new_value"
    otelBaggage.asMap() == otelBaggageFromContext.asMap()
    otelBaggage.asMap() == otelBaggageFromContextOrNull.asMap()

    cleanup:
    otelScope.close()
    ddScope.close()
  }

  @Override
  void cleanup() {
    // Test for context leak
    assert Context.current() == Context.root()
    // Safely reset OTel context storage
    ThreadLocalContextStorage.THREAD_LOCAL_STORAGE.remove()
  }

  private static class CustomData implements ImplicitContextKeyed {
    private static final ContextKey<CustomData> KEY = ContextKey.named('custom')

    @Override
    Context storeInContext(Context context) {
      return context.with(KEY, this)
    }

    private static CustomData fromContext(Context context) {
      return context.get(KEY)
    }
  }
}
