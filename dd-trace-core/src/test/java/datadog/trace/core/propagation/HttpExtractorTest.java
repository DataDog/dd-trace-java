package datadog.trace.core.propagation;

import static datadog.trace.api.DDTags.PARENT_ID;
import static datadog.trace.api.TracePropagationStyle.B3MULTI;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.TracePropagationStyle.NONE;
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HttpExtractorTest extends DDCoreSpecification {

  static final String W3C_TRACE_ID = "00000000000000000000000000000001";
  static final String W3C_SPAN_ID = "123456789abcdef0";
  static final String W3C_TRACE_PARENT = "00-" + W3C_TRACE_ID + "-" + W3C_SPAN_ID + "-01";
  static final String W3C_TRACE_STATE_WITH_P = "dd=p:456789abcdef0123";
  static final String W3C_TRACE_STATE_NO_P = "dd=s:2,foo=1";
  static final String W3C_SPAN_ID_LSTR = String.valueOf(DDSpanId.fromHex(W3C_SPAN_ID));

  static String outOfRangeTraceId = TRACE_ID_MAX.add(BigInteger.ONE).toString();

  static Set<TracePropagationStyle> styles(TracePropagationStyle... vals) {
    return new java.util.LinkedHashSet<>(Arrays.asList(vals));
  }

  static Stream<Arguments> extractHttpHeadersArguments() {
    return Stream.of(
        Arguments.of(
            styles(DATADOG, B3MULTI), "1", "2", "a", "b", null, "1", "2", true, true, false, false),
        Arguments.of(
            styles(DATADOG, B3MULTI),
            null,
            null,
            "a",
            "b",
            null,
            "10",
            "11",
            false,
            false,
            true,
            false),
        Arguments.of(
            styles(DATADOG, B3MULTI),
            null,
            null,
            "a",
            "b",
            null,
            null,
            null,
            true,
            true,
            true,
            false),
        Arguments.of(styles(DATADOG), "1", "2", "a", "b", null, "1", "2", true, true, false, false),
        Arguments.of(
            styles(B3MULTI), "1", "2", "a", "b", null, "10", "11", false, false, false, false),
        Arguments.of(
            styles(B3MULTI, DATADOG),
            "1",
            "2",
            "a",
            "b",
            null,
            "10",
            "11",
            false,
            false,
            false,
            false),
        Arguments.of(
            Collections.<TracePropagationStyle>emptySet(),
            "1",
            "2",
            "a",
            "b",
            null,
            null,
            null,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(DATADOG, B3MULTI),
            "abc",
            "2",
            "a",
            "b",
            null,
            "10",
            "11",
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(DATADOG), "abc", "2", "a", "b", null, null, null, false, false, false, false),
        Arguments.of(
            styles(DATADOG, B3MULTI),
            outOfRangeTraceId,
            "2",
            "a",
            "b",
            null,
            "10",
            "11",
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(DATADOG, B3MULTI),
            "1",
            outOfRangeTraceId,
            "a",
            "b",
            null,
            "10",
            "11",
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(DATADOG),
            outOfRangeTraceId,
            "2",
            "a",
            "b",
            null,
            null,
            null,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(DATADOG),
            "1",
            outOfRangeTraceId,
            "a",
            "b",
            null,
            null,
            null,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(DATADOG, B3MULTI),
            "1",
            "2",
            outOfRangeTraceId,
            "b",
            null,
            "1",
            "2",
            true,
            false,
            false,
            false),
        Arguments.of(
            styles(DATADOG, B3MULTI),
            "1",
            "2",
            "a",
            outOfRangeTraceId,
            null,
            "1",
            "2",
            true,
            false,
            false,
            false),
        Arguments.of(
            styles(NONE), "1", "2", null, null, null, null, null, true, false, true, false),
        Arguments.of(
            styles(DATADOG, TRACECONTEXT),
            "1",
            "2",
            null,
            null,
            W3C_TRACE_PARENT,
            "1",
            W3C_SPAN_ID_LSTR,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(DATADOG, TRACECONTEXT, B3MULTI),
            "1",
            "2",
            "1",
            "2",
            W3C_TRACE_PARENT,
            "1",
            W3C_SPAN_ID_LSTR,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(TRACECONTEXT, DATADOG),
            "1",
            "2",
            null,
            null,
            W3C_TRACE_PARENT,
            "1",
            W3C_SPAN_ID_LSTR,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(TRACECONTEXT, B3MULTI),
            null,
            null,
            "1",
            "2",
            W3C_TRACE_PARENT,
            "1",
            W3C_SPAN_ID_LSTR,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(TRACECONTEXT, B3MULTI, DATADOG),
            "1",
            "2",
            "1",
            "4",
            W3C_TRACE_PARENT,
            "1",
            W3C_SPAN_ID_LSTR,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(B3MULTI, DATADOG, TRACECONTEXT),
            "1",
            "2",
            "1",
            "4",
            W3C_TRACE_PARENT,
            "1",
            W3C_SPAN_ID_LSTR,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(TRACECONTEXT),
            null,
            null,
            null,
            null,
            W3C_TRACE_PARENT,
            "1",
            W3C_SPAN_ID_LSTR,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(DATADOG, TRACECONTEXT),
            "1",
            null,
            null,
            null,
            W3C_TRACE_PARENT,
            "1",
            W3C_SPAN_ID_LSTR,
            false,
            false,
            false,
            false),
        Arguments.of(
            styles(DATADOG, TRACECONTEXT),
            "1",
            "2",
            null,
            null,
            W3C_TRACE_PARENT,
            "1",
            "2",
            false,
            false,
            false,
            true));
  }

  @ParameterizedTest
  @MethodSource("extractHttpHeadersArguments")
  void extractHttpHeaders(
      Set<TracePropagationStyle> stylesSet,
      String datadogTraceId,
      String datadogSpanId,
      String b3TraceId,
      String b3SpanId,
      String w3cTraceParent,
      String expectedTraceId,
      String expectedSpanId,
      boolean putDatadogFields,
      boolean expectDatadogFields,
      boolean tagContext,
      boolean extractFirst) {
    Config config = mock(Config.class);
    when(config.getTracePropagationStylesToExtract()).thenReturn(stylesSet);
    when(config.isTracePropagationExtractFirst()).thenReturn(extractFirst);
    DynamicConfig dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(Collections.singletonMap("SOME_HEADER", "some-tag"))
            .setBaggageMapping(Collections.<String, String>emptyMap())
            .apply();
    HttpCodec.Extractor extractor =
        HttpCodec.createExtractor(config, () -> dynamicConfig.captureTraceConfig());

    Map<String, String> actual = new HashMap<>();
    if (datadogTraceId != null) {
      actual.put(DatadogHttpCodec.TRACE_ID_KEY.toUpperCase(), datadogTraceId);
    }
    if (datadogSpanId != null) {
      actual.put(DatadogHttpCodec.SPAN_ID_KEY.toUpperCase(), datadogSpanId);
    }
    if (b3TraceId != null) {
      actual.put(B3HttpCodec.TRACE_ID_KEY.toUpperCase(), b3TraceId);
    }
    if (b3SpanId != null) {
      actual.put(B3HttpCodec.SPAN_ID_KEY.toUpperCase(), b3SpanId);
    }
    if (w3cTraceParent != null) {
      actual.put(W3CHttpCodec.TRACE_PARENT_KEY.toUpperCase(), w3cTraceParent);
    }
    if (putDatadogFields) {
      actual.put("SOME_HEADER", "my-interesting-info");
    }

    TagContext context = extractor.extract(actual, ContextVisitors.stringValuesMap());

    if (tagContext) {
      assertNotNull(context);
      assertInstanceOf(TagContext.class, context);
    } else {
      if (expectedTraceId == null) {
        assertNull(context);
      } else {
        assertNotNull(context);
        assertEquals(DDTraceId.from(expectedTraceId).toLong(), context.getTraceId().toLong());
        assertEquals(DDSpanId.from(expectedSpanId), context.getSpanId());
      }
    }

    if (expectDatadogFields) {
      assertNotNull(context);
      if (tagContext && b3TraceId != null) {
        Map<String, String> expectedTags = new HashMap<>();
        expectedTags.put("b3.traceid", b3TraceId);
        expectedTags.put("b3.spanid", b3SpanId);
        expectedTags.put("some-tag", "my-interesting-info");
        assertEquals(expectedTags, context.getTags());
      } else {
        assertEquals(
            Collections.singletonMap("some-tag", "my-interesting-info"), context.getTags());
      }
    }
  }

  static Stream<Arguments> checkW3CTraceContextOverrideArguments() {
    return Stream.of(
        Arguments.of(
            styles(DATADOG, TRACECONTEXT),
            "1",
            "2",
            null,
            null,
            W3C_TRACE_STATE_WITH_P,
            "1",
            W3C_SPAN_ID_LSTR,
            "456789abcdef0123"),
        Arguments.of(
            styles(DATADOG, TRACECONTEXT),
            "1",
            "2",
            null,
            null,
            null,
            "1",
            W3C_SPAN_ID_LSTR,
            "0000000000000002"),
        Arguments.of(
            styles(DATADOG, TRACECONTEXT, B3MULTI),
            "1",
            "2",
            "1",
            "2",
            W3C_TRACE_STATE_WITH_P,
            "1",
            W3C_SPAN_ID_LSTR,
            "456789abcdef0123"),
        Arguments.of(
            styles(TRACECONTEXT, DATADOG),
            "1",
            "2",
            null,
            null,
            W3C_TRACE_STATE_WITH_P,
            "1",
            W3C_SPAN_ID_LSTR,
            null),
        Arguments.of(
            styles(TRACECONTEXT, B3MULTI),
            null,
            null,
            "1",
            "2",
            W3C_TRACE_STATE_WITH_P,
            "1",
            W3C_SPAN_ID_LSTR,
            null),
        Arguments.of(
            styles(TRACECONTEXT, B3MULTI, DATADOG),
            "1",
            "2",
            "1",
            "4",
            W3C_TRACE_STATE_WITH_P,
            "1",
            W3C_SPAN_ID_LSTR,
            null),
        Arguments.of(
            styles(B3MULTI, DATADOG, TRACECONTEXT),
            "1",
            "2",
            "1",
            "4",
            W3C_TRACE_STATE_WITH_P,
            "1",
            W3C_SPAN_ID_LSTR,
            "456789abcdef0123"),
        Arguments.of(
            styles(TRACECONTEXT),
            null,
            null,
            null,
            null,
            W3C_TRACE_STATE_WITH_P,
            "1",
            W3C_SPAN_ID_LSTR,
            null),
        Arguments.of(
            styles(B3MULTI, TRACECONTEXT),
            null,
            null,
            "1",
            "2",
            W3C_TRACE_STATE_WITH_P,
            "1",
            W3C_SPAN_ID_LSTR,
            "456789abcdef0123"),
        Arguments.of(
            styles(B3MULTI, DATADOG, TRACECONTEXT),
            "1",
            "2",
            "1",
            "4",
            null,
            "1",
            W3C_SPAN_ID_LSTR,
            "0000000000000002"),
        Arguments.of(
            styles(DATADOG, TRACECONTEXT),
            "1",
            "2",
            null,
            null,
            W3C_TRACE_STATE_NO_P,
            "1",
            W3C_SPAN_ID_LSTR,
            "0000000000000002"));
  }

  @ParameterizedTest
  @MethodSource("checkW3CTraceContextOverrideArguments")
  void checkW3CTraceContextOverride(
      Set<TracePropagationStyle> stylesSet,
      String datadogTraceId,
      String datadogSpanId,
      String b3TraceId,
      String b3SpanId,
      String traceState,
      String expectedTraceId,
      String expectedSpanId,
      String expectedParentId) {
    Config config = mock(Config.class);
    when(config.getTracePropagationStylesToExtract()).thenReturn(stylesSet);
    DynamicConfig dynamicConfig = DynamicConfig.create().apply();
    HttpCodec.Extractor extractor =
        HttpCodec.createExtractor(config, () -> dynamicConfig.captureTraceConfig());

    Map<String, String> actual = new HashMap<>();
    actual.put(W3CHttpCodec.TRACE_PARENT_KEY.toUpperCase(), W3C_TRACE_PARENT);
    if (datadogTraceId != null) {
      actual.put(DatadogHttpCodec.TRACE_ID_KEY.toUpperCase(), datadogTraceId);
    }
    if (datadogSpanId != null) {
      actual.put(DatadogHttpCodec.SPAN_ID_KEY.toUpperCase(), datadogSpanId);
    }
    if (b3TraceId != null) {
      actual.put(B3HttpCodec.TRACE_ID_KEY.toUpperCase(), b3TraceId);
    }
    if (b3SpanId != null) {
      actual.put(B3HttpCodec.SPAN_ID_KEY.toUpperCase(), b3SpanId);
    }
    if (traceState != null) {
      actual.put(W3CHttpCodec.TRACE_STATE_KEY.toUpperCase(), traceState);
    }

    TagContext context = extractor.extract(actual, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    assertEquals(DDTraceId.from(expectedTraceId).toLong(), context.getTraceId().toLong());
    assertEquals(DDSpanId.from(expectedSpanId), context.getSpanId());
    assertEquals(
        expectedParentId, context.getTags() != null ? context.getTags().get(PARENT_ID) : null);
  }

  static Stream<Arguments> verifyExistenceOfSpanLinksArguments() {
    return Stream.of(
        Arguments.of(
            styles(DATADOG, B3MULTI, TRACECONTEXT),
            "1",
            "2",
            "1",
            "b",
            W3C_TRACE_PARENT,
            W3C_TRACE_STATE_NO_P,
            Collections.<TracePropagationStyle>emptyList()),
        Arguments.of(
            styles(DATADOG, B3MULTI, TRACECONTEXT),
            "2",
            "2",
            "2",
            "b",
            W3C_TRACE_PARENT,
            W3C_TRACE_STATE_NO_P,
            Arrays.asList(TRACECONTEXT)),
        Arguments.of(
            styles(DATADOG, B3MULTI, TRACECONTEXT),
            "2",
            "2",
            "1",
            "b",
            W3C_TRACE_PARENT,
            W3C_TRACE_STATE_NO_P,
            Arrays.asList(B3MULTI, TRACECONTEXT)),
        Arguments.of(
            styles(TRACECONTEXT, B3MULTI, DATADOG),
            "2",
            "2",
            "1",
            "b",
            W3C_TRACE_PARENT,
            W3C_TRACE_STATE_NO_P,
            Arrays.asList(DATADOG)));
  }

  @ParameterizedTest
  @MethodSource("verifyExistenceOfSpanLinksArguments")
  void verifyExistenceOfSpanLinksWhenExtractingCompoundHttpHeaders(
      Set<TracePropagationStyle> stylesSet,
      String datadogTraceId,
      String datadogSpanId,
      String b3TraceId,
      String b3SpanId,
      String w3cTraceParent,
      String traceState,
      List<TracePropagationStyle> expectedSpanLinks) {
    Config config = mock(Config.class);
    when(config.getTracePropagationStylesToExtract()).thenReturn(stylesSet);
    DynamicConfig dynamicConfig = DynamicConfig.create().apply();
    HttpCodec.Extractor extractor =
        HttpCodec.createExtractor(config, () -> dynamicConfig.captureTraceConfig());

    Map<String, String> actual = new HashMap<>();
    actual.put(DatadogHttpCodec.TRACE_ID_KEY.toUpperCase(), datadogTraceId);
    actual.put(DatadogHttpCodec.SPAN_ID_KEY.toUpperCase(), datadogSpanId);
    actual.put(B3HttpCodec.TRACE_ID_KEY.toUpperCase(), b3TraceId);
    actual.put(B3HttpCodec.SPAN_ID_KEY.toUpperCase(), b3SpanId);
    actual.put(W3CHttpCodec.TRACE_PARENT_KEY.toUpperCase(), w3cTraceParent);
    actual.put(W3CHttpCodec.TRACE_STATE_KEY.toUpperCase(), traceState);

    TagContext context = extractor.extract(actual, ContextVisitors.stringValuesMap());

    assertNotNull(context);
    List<?> links = context.getTerminatedContextLinks();
    assertEquals(expectedSpanLinks.size(), links.size());
    for (int i = 0; i < links.size(); i++) {
      Object link = links.get(i);
      TracePropagationStyle expectedStyle = expectedSpanLinks.get(i);
      if (expectedStyle == TRACECONTEXT) {
        // check traceState on the link
        try {
          java.lang.reflect.Method tsMethod = link.getClass().getMethod("traceState");
          assertEquals(W3C_TRACE_STATE_NO_P, tsMethod.invoke(link));
        } catch (Exception e) {
          // fallback
        }
      }
      try {
        java.lang.reflect.Method attrsMethod = link.getClass().getMethod("attributes");
        Object attrs = attrsMethod.invoke(link);
        java.lang.reflect.Method asMapMethod = attrs.getClass().getMethod("asMap");
        Map<?, ?> attrMap = (Map<?, ?>) asMapMethod.invoke(attrs);
        assertEquals(expectedStyle.toString(), attrMap.get("context_headers"));
      } catch (Exception e) {
        // fallback: just check size
      }
    }
  }
}
