package opentelemetry14.context;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.ImplicitContextKeyed;
import io.opentelemetry.context.Scope;
import java.util.HashMap;
import java.util.Map;
import opentelemetry14.AbstractOpenTelemetry14Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class ContextTest extends AbstractOpenTelemetry14Test {

  @DisplayName("test Span.current/makeCurrent()")
  @Test
  void testSpanCurrentMakeCurrent() {
    Span otelSpan = this.otelTracer.spanBuilder("some-name").startSpan();

    // Before making current: current span must be invalid or null
    Span currentSpan = Span.current();
    Span currentSpanFromContext = Span.fromContext(Context.current());
    Span currentSpanFromContextOrNull = Span.fromContextOrNull(Context.current());

    assertNotNull(currentSpan);
    assertFalse(currentSpan.getSpanContext().isValid());
    assertNotNull(currentSpanFromContext);
    assertFalse(currentSpanFromContext.getSpanContext().isValid());
    assertNull(currentSpanFromContextOrNull);

    // After making current: OTel span must be current span
    Scope scope = otelSpan.makeCurrent();
    currentSpan = Span.current();
    currentSpanFromContext = Span.fromContext(Context.current());
    currentSpanFromContextOrNull = Span.fromContextOrNull(Context.current());

    assertEquals(otelSpan, currentSpan);
    assertEquals(otelSpan, currentSpanFromContext);
    assertEquals(otelSpan, currentSpanFromContextOrNull);

    // After activating DD span: Datadog span must be current span
    AgentSpan ddSpan = tracer.startSpan("dd-api", "other-name");
    AgentScope ddScope = tracer.activateManualSpan(ddSpan);
    currentSpan = Span.current();

    assertSpanEquals(ddSpan, currentSpan);

    // Cleanup
    ddScope.close();
    ddSpan.finish();
    scope.close();
    otelSpan.end();
  }

  @DisplayName("test Context.makeCurrent() to activate a span without prior active span")
  @Test
  void testContextMakeCurrentWithoutPriorActiveSpan() {
    Span otelSpan = this.otelTracer.spanBuilder("some-name").startSpan();

    // No active span: current span is invalid
    Span currentSpan = Span.current();
    assertNotNull(currentSpan);
    assertFalse(currentSpan.getSpanContext().isValid());

    // Make OTel span current via context
    Context contextWithSpan = Context.current().with(otelSpan);
    Scope scope = contextWithSpan.makeCurrent();
    currentSpan = Span.current();
    assertEquals(otelSpan, currentSpan);

    // After closing scope: current span is invalid again
    scope.close();
    currentSpan = Span.current();
    assertNotNull(currentSpan);
    assertFalse(currentSpan.getSpanContext().isValid());

    // Cleanup
    otelSpan.end();
  }

  @DisplayName("test Context.makeCurrent() to activate a span with another currently active span")
  @Test
  void testContextMakeCurrentWithAnotherActiveSpan() {
    AgentSpan ddSpan = tracer.startSpan("dd-api", "some-name");
    AgentScope ddScope = tracer.activateManualSpan(ddSpan);
    Span otelSpan = this.otelTracer.spanBuilder("other-name").startSpan();

    // DD span is active: current OTel span reflects DD span
    Span currentSpan = Span.current();
    assertNotNull(currentSpan);
    assertSpanEquals(ddSpan, currentSpan);

    // Make OTel span current via context
    Context contextWithSpan = Context.current().with(otelSpan);
    Scope scope = contextWithSpan.makeCurrent();
    currentSpan = Span.current();
    assertEquals(otelSpan, currentSpan);

    // After closing scope: DD span is active again
    scope.close();
    currentSpan = Span.current();
    assertNotNull(currentSpan);
    assertSpanEquals(ddSpan, currentSpan);

    // Cleanup
    otelSpan.end();
    ddScope.close();
    ddSpan.finish();
  }

  @DisplayName("test Context.makeCurrent() to activate an already active span")
  @Test
  void testContextMakeCurrentAlreadyActiveSpan() {
    AgentSpan ddSpan = tracer.startSpan("dd-api", "some-name");
    AgentScope ddScope = tracer.activateManualSpan(ddSpan);
    Span currentSpan = Span.current();

    assertNotNull(currentSpan);
    assertSpanEquals(ddSpan, currentSpan);

    // Make the already-current span current again via context
    Context contextWithSpan = Context.current().with(currentSpan);
    Scope scope = contextWithSpan.makeCurrent();
    currentSpan = Span.current();

    assertNotNull(currentSpan);
    assertSpanEquals(ddSpan, currentSpan);

    // After closing scope: still same DD span
    scope.close();
    currentSpan = Span.current();

    assertNotNull(currentSpan);
    assertSpanEquals(ddSpan, currentSpan);

    // After closing DD scope and finishing: no valid span
    ddScope.close();
    ddSpan.finish();
    currentSpan = Span.current();

    assertNotNull(currentSpan);
    assertFalse(currentSpan.getSpanContext().isValid());
  }

  @DisplayName("test clearing context")
  @Test
  void testClearingContext() {
    Scope rootScope = Context.root().makeCurrent();
    assertEquals(Context.root(), Context.current());
    rootScope.close();
  }

  @DisplayName("test mixing manual and OTel instrumentation")
  @Test
  void testMixingManualAndOtelInstrumentation() {
    Span otelParentSpan = this.otelTracer.spanBuilder("some-name").startSpan();

    // Activate OTel parent span and verify DD active span
    Scope otelParentScope = otelParentSpan.makeCurrent();
    AgentSpan activeSpan = tracer.activeSpan();

    assertEquals("internal", activeSpan.getOperationName().toString());
    assertEquals("some-name", activeSpan.getResourceName().toString());
    assertEquals(
        DDSpanId.toHexStringPadded(activeSpan.getSpanId()),
        otelParentSpan.getSpanContext().getSpanId());

    // Activate DD child span and verify OTel current span
    AgentSpan ddChildSpan = tracer.startSpan("dd-api", "other-name");
    AgentScope ddChildScope = tracer.activateManualSpan(ddChildSpan);
    Span current = Span.current();

    assertEquals(
        DDSpanId.toHexStringPadded(ddChildSpan.getSpanId()), current.getSpanContext().getSpanId());

    // Activate OTel grandchild span and verify DD active span
    Span otelGrandChildSpan = this.otelTracer.spanBuilder("another-name").startSpan();
    Scope otelGrandChildScope = otelGrandChildSpan.makeCurrent();
    activeSpan = tracer.activeSpan();

    assertEquals("internal", activeSpan.getOperationName().toString());
    assertEquals("another-name", activeSpan.getResourceName().toString());
    assertEquals(
        DDSpanId.toHexStringPadded(activeSpan.getSpanId()),
        otelGrandChildSpan.getSpanContext().getSpanId());

    // Close everything and verify trace structure
    otelGrandChildScope.close();
    otelGrandChildSpan.end();
    ddChildScope.close();
    ddChildSpan.finish();
    otelParentScope.close();
    otelParentSpan.end();

    assertTraces(
        trace(
            span().root().operationName("internal").resourceName("some-name"),
            span().childOfPrevious().operationName("other-name"),
            span().childOfPrevious().operationName("internal").resourceName("another-name")));
  }

  @DisplayName("test context spans retrieval")
  @Test
  void testContextSpansRetrieval() {
    Span parentSpan = this.otelTracer.spanBuilder("some-name").startSpan();
    Scope parentScope = parentSpan.makeCurrent();
    ContextKey<Span> currentSpanKey = ContextKey.named("opentelemetry-trace-span-key");
    ContextKey<Span> rootSpanKey = ContextKey.named("opentelemetry-traces-local-root-span");

    // After activating parent: both current and root span keys point to parent
    Context currentContext = Context.current();
    assertEquals(parentSpan, currentContext.get(currentSpanKey));
    assertEquals(parentSpan, currentContext.get(rootSpanKey));

    // After activating child: current span key points to child, root span key still points to
    // parent
    Span childSpan = this.otelTracer.spanBuilder("other-name").startSpan();
    Scope childScope = childSpan.makeCurrent();
    currentContext = Context.current();

    assertEquals(childSpan, currentContext.get(currentSpanKey));
    assertEquals(parentSpan, currentContext.get(rootSpanKey));

    // After closing child: back to parent for both keys
    childScope.close();
    childSpan.end();
    currentContext = Context.current();

    assertEquals(parentSpan, currentContext.get(currentSpanKey));
    assertEquals(parentSpan, currentContext.get(rootSpanKey));

    // Cleanup
    parentScope.close();
    parentSpan.end();
  }

  @DisplayName("test custom object storage")
  @Test
  void testCustomObjectStorage() {
    Context context = Context.root();
    Context originalContext = context;
    CustomData data1 = new CustomData();
    CustomData data2 = new CustomData();

    // Store custom data1
    context = context.with(data1);
    assertEquals(data1, CustomData.fromContext(context));
    assertNull(CustomData.fromContext(originalContext));

    // Replace with custom data2
    context = context.with(data2);
    assertEquals(data2, CustomData.fromContext(context));

    // Remove custom data
    context = context.with(CustomData.KEY, null);
    assertNull(context.get(CustomData.KEY));
  }

  @DisplayName("test Baggage.current/makeCurrent()")
  @Test
  void testBaggageCurrentMakeCurrent() {
    // Initially: current baggage must be empty or null
    Baggage otelBaggage = Baggage.current();
    Baggage otelBaggageFromContext = Baggage.fromContext(Context.current());
    Baggage otelBaggageFromContextOrNull = Baggage.fromContextOrNull(Context.current());

    assertNotNull(otelBaggage);
    assertTrue(otelBaggage.isEmpty());
    assertNotNull(otelBaggageFromContext);
    assertTrue(otelBaggageFromContext.isEmpty());
    assertNull(otelBaggageFromContextOrNull);

    // After making OTel baggage current
    Scope otelScope =
        Baggage.builder()
            .put("foo", "otel_value_to_be_replaced")
            .put("FOO", "OTEL_UNTOUCHED")
            .put("remove_me_key", "otel_remove_me_value")
            .build()
            .makeCurrent();
    otelBaggage = Baggage.current();
    otelBaggageFromContext = Baggage.fromContext(Context.current());
    otelBaggageFromContextOrNull = Baggage.fromContextOrNull(Context.current());

    assertNotNull(otelBaggage);
    assertNotNull(otelBaggageFromContext);
    assertNotNull(otelBaggageFromContextOrNull);
    assertEquals(3, otelBaggage.size());
    assertEquals("otel_value_to_be_replaced", otelBaggage.getEntryValue("foo"));
    assertEquals("OTEL_UNTOUCHED", otelBaggage.getEntryValue("FOO"));
    assertEquals("otel_remove_me_value", otelBaggage.getEntryValue("remove_me_key"));
    assertEquals(otelBaggage.asMap(), otelBaggageFromContext.asMap());
    assertEquals(otelBaggage.asMap(), otelBaggageFromContextOrNull.asMap());

    // After modifying DD baggage
    datadog.context.Context ddContext = datadog.context.Context.current();
    datadog.trace.bootstrap.instrumentation.api.Baggage ddBaggage =
        datadog.trace.bootstrap.instrumentation.api.Baggage.fromContext(ddContext);
    assertNotNull(ddBaggage);
    ddBaggage.addItem("new_foo", "dd_new_value");
    ddBaggage.addItem("foo", "dd_overwrite_value");
    ddBaggage.removeItem("remove_me_key");
    datadog.context.ContextScope ddScope = ddContext.with(ddBaggage).attach();
    otelBaggage = Baggage.current();
    otelBaggageFromContext = Baggage.fromContext(Context.current());
    otelBaggageFromContextOrNull = Baggage.fromContextOrNull(Context.current());

    assertNotNull(otelBaggage);
    assertNotNull(otelBaggageFromContext);
    assertNotNull(otelBaggageFromContextOrNull);
    assertEquals(3, otelBaggage.size());
    assertEquals("dd_overwrite_value", otelBaggage.getEntryValue("foo"));
    assertEquals("OTEL_UNTOUCHED", otelBaggage.getEntryValue("FOO"));
    assertEquals("dd_new_value", otelBaggage.getEntryValue("new_foo"));
    assertEquals(otelBaggage.asMap(), otelBaggageFromContext.asMap());
    assertEquals(otelBaggage.asMap(), otelBaggageFromContextOrNull.asMap());

    // After closing both scopes: current baggage must be empty
    ddScope.close();
    otelScope.close();
    assertTrue(Baggage.current().isEmpty());

    // Create DD baggage from map and activate
    ddContext = datadog.context.Context.current();
    Map<String, String> ddBaggageItems = new HashMap<>();
    ddBaggageItems.put("foo", "dd_value_to_be_replaced");
    ddBaggageItems.put("FOO", "DD_UNTOUCHED");
    ddBaggageItems.put("remove_me_key", "dd_remove_me_value");
    ddBaggage = datadog.trace.bootstrap.instrumentation.api.Baggage.create(ddBaggageItems);
    ddScope = ddContext.with(ddBaggage).attach();
    otelBaggage = Baggage.current();
    otelBaggageFromContext = Baggage.fromContext(Context.current());
    otelBaggageFromContextOrNull = Baggage.fromContextOrNull(Context.current());

    assertNotNull(otelBaggage);
    assertNotNull(otelBaggageFromContext);
    assertNotNull(otelBaggageFromContextOrNull);
    assertEquals(3, otelBaggage.size());
    assertEquals("dd_value_to_be_replaced", otelBaggage.getEntryValue("foo"));
    assertEquals("DD_UNTOUCHED", otelBaggage.getEntryValue("FOO"));
    assertEquals("dd_remove_me_value", otelBaggage.getEntryValue("remove_me_key"));
    assertEquals(otelBaggage.asMap(), otelBaggageFromContext.asMap());
    assertEquals(otelBaggage.asMap(), otelBaggageFromContextOrNull.asMap());

    // Build modified OTel baggage from existing
    io.opentelemetry.api.baggage.BaggageBuilder builder = otelBaggage.toBuilder();
    builder.put("new_foo", "otel_new_value");
    builder.put("foo", "otel_overwrite_value");
    builder.remove("remove_me_key");
    otelScope = builder.build().makeCurrent();
    otelBaggage = Baggage.current();
    otelBaggageFromContext = Baggage.fromContext(Context.current());
    otelBaggageFromContextOrNull = Baggage.fromContextOrNull(Context.current());

    assertNotNull(otelBaggage);
    assertNotNull(otelBaggageFromContext);
    assertNotNull(otelBaggageFromContextOrNull);
    assertEquals(3, otelBaggage.size());
    assertEquals("otel_overwrite_value", otelBaggage.getEntryValue("foo"));
    assertEquals("DD_UNTOUCHED", otelBaggage.getEntryValue("FOO"));
    assertEquals("otel_new_value", otelBaggage.getEntryValue("new_foo"));
    assertEquals(otelBaggage.asMap(), otelBaggageFromContext.asMap());
    assertEquals(otelBaggage.asMap(), otelBaggageFromContextOrNull.asMap());

    // Cleanup
    otelScope.close();
    ddScope.close();
  }

  // Using Object for ddSpan instead of AgentSpan as bootstrap types can't be referred in API
  private static void assertSpanEquals(Object ddSpan, Span currentSpan) {
    AgentSpan expectedDddSpan = (AgentSpan) ddSpan;
    assertEquals(
        expectedDddSpan.getTraceId().toHexStringPadded(32),
        currentSpan.getSpanContext().getTraceId());
    assertEquals(
        DDSpanId.toHexStringPadded(expectedDddSpan.getSpanId()),
        currentSpan.getSpanContext().getSpanId());
  }

  private static class CustomData implements ImplicitContextKeyed {
    static final ContextKey<CustomData> KEY = ContextKey.named("custom");

    @Override
    public Context storeInContext(Context context) {
      return context.with(KEY, this);
    }

    static CustomData fromContext(Context context) {
      return context.get(KEY);
    }
  }
}
