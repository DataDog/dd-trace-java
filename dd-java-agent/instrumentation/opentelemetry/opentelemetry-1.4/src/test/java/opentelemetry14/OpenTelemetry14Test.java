package opentelemetry14;

import static datadog.opentelemetry.shim.trace.OtelSpanEventTestHelper.stringifyErrorStack;
import static datadog.trace.agent.test.assertions.Matchers.any;
import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.DDTags.ERROR_MSG;
import static datadog.trace.api.DDTags.ERROR_STACK;
import static datadog.trace.api.DDTags.ERROR_TYPE;
import static datadog.trace.api.DDTags.SPAN_EVENTS;
import static datadog.trace.api.DDTags.SPAN_LINKS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static io.opentelemetry.api.common.AttributeKey.booleanArrayKey;
import static io.opentelemetry.api.common.AttributeKey.doubleArrayKey;
import static io.opentelemetry.api.common.AttributeKey.longArrayKey;
import static io.opentelemetry.api.common.AttributeKey.stringArrayKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static io.opentelemetry.api.trace.SpanKind.CLIENT;
import static io.opentelemetry.api.trace.SpanKind.CONSUMER;
import static io.opentelemetry.api.trace.SpanKind.INTERNAL;
import static io.opentelemetry.api.trace.SpanKind.PRODUCER;
import static io.opentelemetry.api.trace.SpanKind.SERVER;
import static io.opentelemetry.api.trace.StatusCode.ERROR;
import static io.opentelemetry.api.trace.StatusCode.OK;
import static io.opentelemetry.api.trace.StatusCode.UNSET;
import static java.lang.String.join;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.opentelemetry.shim.trace.OtelSpanEvent;
import datadog.trace.agent.test.assertions.Matcher;
import datadog.trace.agent.test.assertions.Matchers;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.bootstrap.instrumentation.api.WithAgentSpan;
import datadog.trace.core.DDSpan;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import opentelemetry14.context.propagation.TextMap;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.skyscreamer.jsonassert.JSONAssert;

class OpenTelemetry14Test extends AbstractOpenTelemetry14Test {
  private static final String SPAN_KIND_INTERNAL = "internal";
  private static final long TIME_MILLIS = 1723220824705L;
  private static final long TIME_NANO = TIME_MILLIS * 1_000_000L;

  @Test
  void testParentSpanUsingActiveSpan() {
    Span parentSpan = this.otelTracer.spanBuilder("some-name").startSpan();
    try (Scope ignoredScope = parentSpan.makeCurrent()) {
      Span childSpan = this.otelTracer.spanBuilder("other-name").startSpan();
      childSpan.end();
    }
    parentSpan.end();

    assertTraces(
        trace(
            span().root().operationName("internal").resourceName("some-name"),
            span().childOfPrevious().operationName("internal").resourceName("other-name")));
  }

  @Test
  void testParentSpanUsingReference() {
    Span parentSpan = this.otelTracer.spanBuilder("some-name").startSpan();
    Span childSpan =
        this.otelTracer
            .spanBuilder("other-name")
            .setParent(Context.current().with(parentSpan))
            .startSpan();
    childSpan.end();
    parentSpan.end();

    assertTraces(
        trace(
            span().root().operationName("internal").resourceName("some-name"),
            span().childOfPrevious().operationName("internal").resourceName("other-name")));
  }

  @Test
  void testParentSpanUsingPropagationData() {
    String traceId = "00000000000000001111111111111111";
    String spanId = "2222222222222222";
    String traceParent = "00-" + traceId + "-" + spanId + "-00";
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", traceParent);
    TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();
    Context context = propagator.extract(Context.root(), headers, TextMap.INSTANCE);

    try (Scope ignoredScope = context.makeCurrent()) {
      Span childSpan = this.otelTracer.spanBuilder("some-name").startSpan();
      childSpan.end();
    }

    assertTraces(
        trace(
            span()
                .traceId(DDTraceId.fromHex(traceId))
                .childOf(DDSpanId.fromHex(spanId))
                .operationName("internal")
                .resourceName("some-name")));
  }

  @Test
  void testParentSpanUsingInvalidReference() throws Exception {
    // Root context contains a SpanContext with TID/SID to 0 as current span
    Context invalidCurrentSpanContext = Context.root();
    Span childSpan =
        this.otelTracer.spanBuilder("some-name").setParent(invalidCurrentSpanContext).startSpan();
    childSpan.end();

    writer.waitForTraces(1);
    List<DDSpan> firstTrace = writer.firstTrace();

    assertEquals(1, firstTrace.size());
    assertNotEquals(0, firstTrace.get(0).getSpanId());
  }

  @Test
  void testNoParentToCreateNewRootSpan() {
    Span parentSpan = this.otelTracer.spanBuilder("some-name").startSpan();
    try (Scope ignoredScope = parentSpan.makeCurrent()) {
      Span childSpan = this.otelTracer.spanBuilder("other-name").setNoParent().startSpan();
      childSpan.end();
    }
    parentSpan.end();

    assertTraces(
        trace(span().root().operationName("internal").resourceName("some-name")),
        trace(span().root().operationName("internal").resourceName("other-name")));
  }

  @Test
  void testAddEvent() {
    SpanBuilder builder = this.otelTracer.spanBuilder("some-name");
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(1000);
    OtelSpanEvent.setTimeSource(timeSource);

    Span result = builder.startSpan();
    result.addEvent("event");
    result.end();

    String expectedEventTag =
        "["
            + "{ \"time_unix_nano\": "
            + timeSource.getCurrentTimeNanos()
            + ", \"name\": \"event\" }"
            + "]";
    assertTraces(
        trace(
            span()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(SPAN_EVENTS, isJson(expectedEventTag)))));
  }

  static Stream<Arguments> testAddSingleEventArguments() {
    return Stream.of(
        arguments(
            "empty attributes", "event1", TIME_MILLIS, MILLISECONDS, Attributes.empty(), null),
        arguments(
            "scalar attributes",
            "event2",
            TIME_NANO,
            NANOSECONDS,
            Attributes.builder()
                .put("string-key", "string-value")
                .put("long-key", 123456789L)
                .put("double-key", 1234.5678)
                .put("boolean-key-true", true)
                .put("boolean-key-false", false)
                .build(),
            "{\"string-key\": \"string-value\", \"long-key\": 123456789, \"double-key\": 1234.5678, \"boolean-key-true\": true, \"boolean-key-false\": false }"),
        arguments(
            "array attributes",
            "event3",
            TIME_NANO,
            NANOSECONDS,
            Attributes.builder()
                .put("string-key-array", "string-value1", "string-value2", "string-value3")
                .put("long-key-array", 123456L, 1234567L, 12345678L)
                .put("double-key-array", 1234.5D, 1234.56D, 1234.567D)
                .put("boolean-key-array", true, false, true)
                .build(),
            "{\"string-key-array\": [ \"string-value1\", \"string-value2\", \"string-value3\" ], \"long-key-array\": [ 123456, 1234567, 12345678 ], \"double-key-array\": [ 1234.5, 1234.56, 1234.567], \"boolean-key-array\": [true, false, true] }"));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testAddSingleEventArguments")
  void testAddSingleEvent(
      String scenario,
      String name,
      long timestamp,
      TimeUnit unit,
      Attributes attributes,
      String expectedAttributes) {
    SpanBuilder builder = this.otelTracer.spanBuilder("some-name");
    String expectedEventTag =
        "["
            + "{ \"time_unix_nano\": "
            + unit.toNanos(timestamp)
            + ", \"name\": \""
            + name
            + "\""
            + (expectedAttributes == null ? "" : ", \"attributes\": " + expectedAttributes)
            + " }"
            + "]";

    Span result = builder.startSpan();
    result.addEvent(name, attributes, timestamp, unit);
    result.end();

    assertTraces(
        trace(
            span()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(SPAN_EVENTS, isJson(expectedEventTag)))));
  }

  @Test
  void testAddMultipleSpanEvents() {
    SpanBuilder builder = this.otelTracer.spanBuilder("some-name");

    Span result = builder.startSpan();
    result.addEvent("event1", Attributes.empty(), TIME_NANO, NANOSECONDS);
    result.addEvent(
        "event2",
        Attributes.builder().put("string-key", "string-value").build(),
        TIME_NANO,
        NANOSECONDS);
    result.end();

    String expectedEventTag =
        "["
            + "{ \"time_unix_nano\": "
            + TIME_NANO
            + ", \"name\": \"event1\" },"
            + "{ \"time_unix_nano\": "
            + TIME_NANO
            + ", \"name\": \"event2\", \"attributes\": {\"string-key\": \"string-value\"} }"
            + "]";
    assertTraces(
        trace(
            span()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(SPAN_EVENTS, isJson(expectedEventTag)))));
  }

  @Test
  void testSimpleSpanLinks() {
    String traceId = "1234567890abcdef1234567890abcdef";
    String spanId = "fedcba0987654321";
    TraceState traceState = TraceState.builder().put("string-key", "string-value").build();

    String expectedLinksTag =
        "["
            + "{ \"trace_id\": \""
            + traceId
            + "\", \"span_id\": \""
            + spanId
            + "\", \"flags\": 1, \"tracestate\": \"string-key=string-value\" }"
            + "]";

    Span span =
        this.otelTracer
            .spanBuilder("some-name")
            .addLink(SpanContext.getInvalid())
            .addLink(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), traceState))
            .startSpan();
    span.end();

    assertTraces(
        trace(
            span()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(SPAN_LINKS, isJson(expectedLinksTag)))));
  }

  @Test
  void testMultipleSpanLinks() {
    SpanBuilder spanBuilder = this.otelTracer.spanBuilder("some-name");

    List<String> expectedLinks = new ArrayList<>();
    for (int i = 0; i <= 9; i++) {
      String traceId = "1234567890abcdef1234567890abcde" + i;
      String spanId = "fedcba098765432" + i;
      TraceState traceState = TraceState.builder().put("string-key", "string-value" + i).build();
      spanBuilder.addLink(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), traceState));
      expectedLinks.add(
          "{ \"trace_id\": \""
              + traceId
              + "\", \"span_id\": \""
              + spanId
              + "\", \"flags\": 1, \"tracestate\": \"string-key=string-value"
              + i
              + "\" }");
    }
    spanBuilder.startSpan().end();

    String expectedLinksTag = "[" + join(",", expectedLinks) + "]";
    assertTraces(
        trace(
            span()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(SPAN_LINKS, isJson(expectedLinksTag)))));
  }

  static Stream<Arguments> testSpanLinkAttributesArguments() {
    return Stream.of(
        arguments("empty attributes", Attributes.empty(), null),
        arguments(
            "scalar attributes",
            Attributes.builder()
                .put("string-key", "string-value")
                .put("long-key", 123456789L)
                .put("double-key", 1234.5678)
                .put("boolean-key-true", true)
                .put("boolean-key-false", false)
                .build(),
            "{ \"string-key\": \"string-value\", \"long-key\": \"123456789\", \"double-key\": \"1234.5678\", \"boolean-key-true\": \"true\", \"boolean-key-false\": \"false\" }"),
        arguments(
            "array attributes",
            Attributes.builder()
                .put("string-key-array", "string-value1", "string-value2", "string-value3")
                .put("long-key-array", 123456L, 1234567L, 12345678L)
                .put("double-key-array", 1234.5D, 1234.56D, 1234.567D)
                .put("boolean-key-array", true, false, true)
                .build(),
            "{ \"string-key-array.0\": \"string-value1\", \"string-key-array.1\": \"string-value2\", \"string-key-array.2\": \"string-value3\", \"long-key-array.0\": \"123456\", \"long-key-array.1\": \"1234567\", \"long-key-array.2\": \"12345678\", \"double-key-array.0\": \"1234.5\", \"double-key-array.1\": \"1234.56\", \"double-key-array.2\": \"1234.567\", \"boolean-key-array.0\": \"true\", \"boolean-key-array.1\": \"false\", \"boolean-key-array.2\": \"true\" }"));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testSpanLinkAttributesArguments")
  void testSpanLinkAttributes(String scenario, Attributes attributes, String expectedAttributes) {
    String traceId = "1234567890abcdef1234567890abcdef";
    String spanId = "fedcba0987654321";
    TraceState traceState = TraceState.builder().put("string-key", "string-value").build();
    Span span =
        this.otelTracer
            .spanBuilder("some-name")
            .addLink(
                SpanContext.create(traceId, spanId, TraceFlags.getSampled(), traceState),
                attributes)
            .startSpan();
    span.end();

    String expectedLinksTag =
        "["
            + "{ \"trace_id\": \""
            + traceId
            + "\", \"span_id\": \""
            + spanId
            + "\", \"flags\": 1, \"tracestate\": \"string-key=string-value\""
            + (expectedAttributes == null ? "" : ", \"attributes\": " + expectedAttributes)
            + " }"
            + "]";

    assertTraces(
        trace(
            span()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(SPAN_LINKS, isJson(expectedLinksTag)))));
  }

  static Stream<Arguments> testSpanLinksTraceStateArguments() {
    return Stream.of(
        arguments("default trace state", TraceState.getDefault(), null),
        arguments(
            "single key-value", TraceState.builder().put("key", "value").build(), "key=value"),
        arguments(
            "multiple key-values",
            TraceState.builder()
                .put("key1", "value1")
                .put("key2", "value2")
                .put("key3", "value3")
                .put("key4", "value4")
                .put("key5", "value5")
                .build(),
            "key5=value5,key4=value4,key3=value3,key2=value2,key1=value1"));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testSpanLinksTraceStateArguments")
  void testSpanLinksTraceState(String scenario, TraceState traceState, String expectedTraceState) {
    String traceId = "1234567890abcdef1234567890abcdef";
    String spanId = "fedcba0987654321";

    Span span =
        this.otelTracer
            .spanBuilder("some-name")
            .addLink(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), traceState))
            .startSpan();
    span.end();

    String expectedTraceStateJson =
        expectedTraceState == null ? "" : ", \"tracestate\": \"" + expectedTraceState + "\"";
    String expectedLinksTag =
        "["
            + "{ \"trace_id\": \""
            + traceId
            + "\", \"span_id\": \""
            + spanId
            + "\", \"flags\": 1"
            + expectedTraceStateJson
            + " }"
            + "]";

    assertTraces(
        trace(
            span()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(SPAN_LINKS, isJson(expectedLinksTag)))));
  }

  static Stream<Arguments> testSpanAttributesArguments() {
    return Stream.of(
        arguments("builder only", true, false),
        arguments("builder and span", true, true),
        arguments("neither", false, false),
        arguments("span only", false, true));
  }

  @SuppressWarnings(
      "DataFlowIssue") // Allow null values on non-null parameters for thorough testing
  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testSpanAttributesArguments")
  void testSpanAttributes(String scenario, boolean tagBuilder, boolean tagSpan) {
    SpanBuilder builder = this.otelTracer.spanBuilder("some-name");
    if (tagBuilder) {
      builder
          .setAttribute(DDTags.RESOURCE_NAME, "some-resource")
          .setAttribute("string", "a")
          .setAttribute("null-string", null)
          .setAttribute("empty_string", "")
          .setAttribute("number", 1)
          .setAttribute("boolean", true)
          .setAttribute(stringKey("null-string-attribute"), null)
          .setAttribute(stringKey("empty-string-attribute"), "")
          .setAttribute(stringArrayKey("string-array"), asList("a", "b", "c"))
          .setAttribute(booleanArrayKey("boolean-array"), asList(true, false))
          .setAttribute(longArrayKey("long-array"), asList(1L, 2L, 3L, 4L))
          .setAttribute(doubleArrayKey("double-array"), asList(1.23D, 4.56D))
          .setAttribute(stringArrayKey("empty-array"), emptyList())
          .setAttribute(stringArrayKey("null-array"), null);
    }
    Span result = builder.startSpan();
    if (tagSpan) {
      result.setAttribute(DDTags.RESOURCE_NAME, "other-resource");
      result.setAttribute("string", "b");
      result.setAttribute("empty_string", "");
      result.setAttribute("number", 2);
      result.setAttribute("boolean", false);
      result.setAttribute(stringKey("null-string-attribute"), null);
      result.setAttribute(stringKey("empty-string-attribute"), "");
      result.setAttribute(stringArrayKey("string-array"), asList("d", "e", "f"));
      result.setAttribute(booleanArrayKey("boolean-array"), asList(false, true));
      result.setAttribute(longArrayKey("long-array"), asList(5L, 6L, 7L, 8L));
      result.setAttribute(doubleArrayKey("double-array"), asList(2.34D, 5.67D));
      result.setAttribute(stringArrayKey("empty-array"), emptyList());
      result.setAttribute(stringArrayKey("null-array"), null);
    }

    result.end();

    String expectedResource;
    if (tagSpan) {
      expectedResource = "other-resource";
    } else if (tagBuilder) {
      expectedResource = "some-resource";
    } else {
      expectedResource = "some-name";
    }

    TagsMatcher[] tagsMatchers;
    if (tagSpan) {
      tagsMatchers =
          new TagsMatcher[] {
            defaultTags(),
            tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
            tag("string", is("b")),
            tag("empty_string", is("")),
            tag("number", is(2L)),
            tag("boolean", is(false)),
            tag("empty-string-attribute", is("")),
            tag("string-array.0", is("d")),
            tag("string-array.1", is("e")),
            tag("string-array.2", is("f")),
            tag("boolean-array.0", is(false)),
            tag("boolean-array.1", is(true)),
            tag("long-array.0", is(5L)),
            tag("long-array.1", is(6L)),
            tag("long-array.2", is(7L)),
            tag("long-array.3", is(8L)),
            tag("double-array.0", is(2.34D)),
            tag("double-array.1", is(5.67D)),
            tag("empty-array", is(""))
          };
    } else if (tagBuilder) {
      tagsMatchers =
          new TagsMatcher[] {
            defaultTags(),
            tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
            tag("string", is("a")),
            tag("empty_string", is("")),
            tag("number", is(1L)),
            tag("boolean", is(true)),
            tag("empty-string-attribute", is("")),
            tag("string-array.0", is("a")),
            tag("string-array.1", is("b")),
            tag("string-array.2", is("c")),
            tag("boolean-array.0", is(true)),
            tag("boolean-array.1", is(false)),
            tag("long-array.0", is(1L)),
            tag("long-array.1", is(2L)),
            tag("long-array.2", is(3L)),
            tag("long-array.3", is(4L)),
            tag("double-array.0", is(1.23D)),
            tag("double-array.1", is(4.56D)),
            tag("empty-array", is(""))
          };
    } else {
      tagsMatchers = new TagsMatcher[] {defaultTags(), tag(SPAN_KIND, is(SPAN_KIND_INTERNAL))};
    }

    assertTraces(
        trace(
            span()
                .root()
                .operationName("internal")
                .resourceName(expectedResource)
                .error(false)
                .tags(tagsMatchers)));
  }

  @Test
  void testIntegrationName() throws Exception {
    Span span = this.otelTracer.spanBuilder("some-name").startSpan();
    span.end();

    writer.waitForTraces(1);
    DDSpan ddSpan = writer.firstTrace().get(0);
    assertEquals("otel", ddSpan.context().getIntegrationName().toString());
  }

  static Stream<Arguments> testSpanKindsArguments() {
    return Stream.of(
        arguments(INTERNAL, SPAN_KIND_INTERNAL),
        arguments(SERVER, SPAN_KIND_SERVER),
        arguments(CLIENT, SPAN_KIND_CLIENT),
        arguments(PRODUCER, SPAN_KIND_PRODUCER),
        arguments(CONSUMER, SPAN_KIND_CONSUMER));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testSpanKindsArguments")
  void testSpanKinds(SpanKind otelSpanKind, String tagSpanKind) {
    this.otelTracer.spanBuilder("some-name").setSpanKind(otelSpanKind).startSpan().end();

    assertTraces(trace(span().tags(defaultTags(), tag(SPAN_KIND, is(tagSpanKind)))));
  }

  @Test
  void testSpanErrorStatus() {
    Span result = this.otelTracer.spanBuilder("some-name").startSpan();
    result.setStatus(ERROR, "some-error");
    result.end();

    assertTraces(
        trace(
            span()
                .root()
                .operationName("internal")
                .resourceName("some-name")
                .error(true)
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(ERROR_MSG, is("some-error")))));
  }

  @Test
  void testSpanStatusTransition() {
    Span result = this.otelTracer.spanBuilder("some-name").startSpan();
    DDSpan delegate = getDDSpan(result);

    // UNSET
    result.setStatus(UNSET);
    assertFalse(delegate.isError());
    assertNull(delegate.getTag(ERROR_MSG));

    // ERROR
    result.setStatus(ERROR, "some error");
    assertTrue(delegate.isError());
    assertEquals("some error", delegate.getTag(ERROR_MSG));

    // UNSET after ERROR (should not clear error)
    result.setStatus(UNSET);
    assertTrue(delegate.isError());
    assertEquals("some error", delegate.getTag(ERROR_MSG));

    // OK (should clear error)
    result.setStatus(OK);
    assertFalse(delegate.isError());
    assertNull(delegate.getTag(ERROR_MSG));

    result.end();

    assertTraces(
        trace(
            span()
                .root()
                .operationName("internal")
                .resourceName("some-name")
                .error(false)
                .tags(defaultTags(), tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)))));
  }

  static Stream<Arguments> testSpanRecordExceptionArguments() {
    return Stream.of(
        arguments(
            "basic exception",
            new NullPointerException("Null pointer"),
            Attributes.empty(),
            null,
            null,
            null,
            null),
        arguments(
            "overridden message",
            new NumberFormatException("Number format exception"),
            Attributes.builder().put("exception.message", "something-else").build(),
            "something-else",
            null,
            null,
            null),
        arguments(
            "overridden type",
            new NullPointerException("Null pointer"),
            Attributes.builder().put("exception.type", "CustomType").build(),
            null,
            "CustomType",
            null,
            null),
        arguments(
            "overridden stacktrace",
            new NullPointerException("Null pointer"),
            Attributes.builder().put("exception.stacktrace", "CustomTrace").build(),
            null,
            null,
            "CustomTrace",
            null),
        arguments(
            "extra attributes",
            new NullPointerException("Null pointer"),
            Attributes.builder().put("key", "value").build(),
            null,
            null,
            null,
            ", \"key\": \"value\""));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testSpanRecordExceptionArguments")
  void testSpanRecordException(
      String scenario,
      Exception exception,
      Attributes attributes,
      String overriddenMessage,
      String overriddenType,
      String overriddenStacktrace,
      String extraJson) {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(1000);
    OtelSpanEvent.setTimeSource(timeSource);

    Span result = this.otelTracer.spanBuilder("some-name").startSpan();
    result.recordException(exception, attributes);
    result.end();

    String errorMessage = overriddenMessage != null ? overriddenMessage : exception.getMessage();
    String errorType = overriddenType != null ? overriddenType : exception.getClass().getName();
    String errorStackTrace =
        overriddenStacktrace != null ? overriddenStacktrace : stringifyErrorStack(exception);
    String expectedAttributes =
        "{"
            + "\"exception.message\": \""
            + errorMessage
            + "\", \"exception.type\": \""
            + errorType
            + "\", \"exception.stacktrace\": \""
            + errorStackTrace
            + "\""
            + (extraJson != null ? extraJson : "")
            + "}";

    String expectedEventTag =
        "["
            + "{ \"time_unix_nano\": "
            + timeSource.getCurrentTimeNanos()
            + ", \"name\": \"exception\", \"attributes\": "
            + expectedAttributes
            + " }"
            + "]";

    assertTraces(
        trace(
            span()
                .root()
                .operationName("internal")
                .resourceName("some-name")
                .error(false)
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(SPAN_EVENTS, isJson(expectedEventTag)),
                    tag(ERROR_MSG, is(errorMessage)),
                    tag(ERROR_TYPE, is(errorType)),
                    tag(ERROR_STACK, is(errorStackTrace)))));
  }

  @Test
  void testSpanErrorReflectLastException() {
    Span span = this.otelTracer.spanBuilder("some-name").startSpan();
    NullPointerException firstException = new NullPointerException("Null pointer");
    NumberFormatException lastException = new NumberFormatException("Number format exception");

    span.recordException(firstException);
    span.recordException(lastException);
    span.end();

    assertTraces(
        trace(
            span()
                .root()
                .operationName("internal")
                .resourceName("some-name")
                .error(false)
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(SPAN_EVENTS, any()),
                    tag(ERROR_MSG, is(lastException.getMessage())),
                    tag(ERROR_TYPE, is(lastException.getClass().getName())),
                    tag(ERROR_STACK, is(stringifyErrorStack(lastException))))));
  }

  @Test
  void testSpanNameUpdate() {
    Span result = this.otelTracer.spanBuilder("some-name").setSpanKind(SERVER).startSpan();
    DDSpan delegate = getDDSpan(result);

    assertEquals(SPAN_KIND_INTERNAL, delegate.getOperationName().toString());
    assertEquals("some-name", delegate.getResourceName().toString());

    result.updateName("other-name");

    assertEquals(SPAN_KIND_INTERNAL, delegate.getOperationName().toString());
    assertEquals("other-name", delegate.getResourceName().toString());

    result.end();

    assertTraces(trace(span().root().operationName("server.request").resourceName("other-name")));
  }

  @Test
  void testSpanUpdateAfterEnd() {
    Span result = this.otelTracer.spanBuilder("some-name").startSpan();

    result.setAttribute("string", "value");
    result.setStatus(ERROR);
    result.end();
    result.updateName("other-name");
    result.setAttribute("string", "other-value");
    result.setStatus(OK);
    result.addEvent("event");
    result.recordException(new Throwable());

    assertTraces(
        trace(
            span()
                .root()
                .operationName("internal")
                .resourceName("some-name")
                .error(true)
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag("string", is("value")))));
  }

  private static DDSpan getDDSpan(Span span) {
    return (DDSpan) ((WithAgentSpan) span).asAgentSpan();
  }

  private static Matcher<String> isJson(String expected) {
    return Matchers.validates(
        s -> {
          try {
            JSONAssert.assertEquals(expected, s, true);
            return true;
          } catch (JSONException e) {
            return false;
          }
        });
  }
}
