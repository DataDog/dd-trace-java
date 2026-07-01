package datadog.trace.core.propagation;

import static datadog.trace.api.DDTags.PARENT_ID;
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.B3HttpCodec.B3_SPAN_ID;
import static datadog.trace.core.propagation.B3HttpCodec.B3_TRACE_ID;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

class HttpExtractorTest extends DDJavaSpecification {

  private static final String W3C_TRACE_ID = "00000000000000000000000000000001";
  private static final String W3C_SPAN_ID = "123456789abcdef0";
  private static final String W3C_TRACE_PARENT = "00-" + W3C_TRACE_ID + "-" + W3C_SPAN_ID + "-01";
  private static final String W3C_PARENT_ID = "456789abcdef0123";
  private static final String W3C_TRACE_STATE_WITH_P = "dd=p:" + W3C_PARENT_ID;
  private static final String W3C_TRACE_STATE_NO_P = "dd=s:2,foo=1";
  private static final String W3C_SPAN_ID_LSTR = Long.toString(DDSpanId.fromHex(W3C_SPAN_ID));

  @TableTest({
    "scenario                                  | styles                           | datadogTraceId         | datadogSpanId          | b3TraceId              | b3SpanId               | w3cTraceParent     | expectedTraceId | expectedSpanId     | putDatadogFields | expectDatadogFields | tagContext | extractFirst",
    "DATADOG,B3MULTI ids                       | [DATADOG, B3MULTI]               | '1'                    | '2'                    | 'a'                    | 'b'                    |                    | '1'             | '2'                | true             | true                | false      | false       ",
    "DATADOG,B3MULTI b3 only                   | [DATADOG, B3MULTI]               |                        |                        | 'a'                    | 'b'                    |                    | '10'            | '11'               | false            | false               | true       | false       ",
    "DATADOG,B3MULTI b3 only with dd field     | [DATADOG, B3MULTI]               |                        |                        | 'a'                    | 'b'                    |                    |                 |                    | true             | true                | true       | false       ",
    "DATADOG only                              | [DATADOG]                        | '1'                    | '2'                    | 'a'                    | 'b'                    |                    | '1'             | '2'                | true             | true                | false      | false       ",
    "B3MULTI only                              | [B3MULTI]                        | '1'                    | '2'                    | 'a'                    | 'b'                    |                    | '10'            | '11'               | false            | false               | false      | false       ",
    "B3MULTI,DATADOG                           | [B3MULTI, DATADOG]               | '1'                    | '2'                    | 'a'                    | 'b'                    |                    | '10'            | '11'               | false            | false               | false      | false       ",
    "no styles                                 | []                               | '1'                    | '2'                    | 'a'                    | 'b'                    |                    |                 |                    | false            | false               | false      | false       ",
    "DATADOG,B3MULTI invalid datadog trace     | [DATADOG, B3MULTI]               | 'abc'                  | '2'                    | 'a'                    | 'b'                    |                    | '10'            | '11'               | false            | false               | false      | false       ",
    "DATADOG only invalid trace                | [DATADOG]                        | 'abc'                  | '2'                    | 'a'                    | 'b'                    |                    |                 |                    | false            | false               | false      | false       ",
    "DATADOG,B3MULTI dd trace out of range     | [DATADOG, B3MULTI]               | '18446744073709551616' | '2'                    | 'a'                    | 'b'                    |                    | '10'            | '11'               | false            | false               | false      | false       ",
    "DATADOG,B3MULTI dd span out of range      | [DATADOG, B3MULTI]               | '1'                    | '18446744073709551616' | 'a'                    | 'b'                    |                    | '10'            | '11'               | false            | false               | false      | false       ",
    "DATADOG only dd trace out of range        | [DATADOG]                        | '18446744073709551616' | '2'                    | 'a'                    | 'b'                    |                    |                 |                    | false            | false               | false      | false       ",
    "DATADOG only dd span out of range         | [DATADOG]                        | '1'                    | '18446744073709551616' | 'a'                    | 'b'                    |                    |                 |                    | false            | false               | false      | false       ",
    "DATADOG,B3MULTI b3 trace out of range     | [DATADOG, B3MULTI]               | '1'                    | '2'                    | '18446744073709551616' | 'b'                    |                    | '1'             | '2'                | true             | false               | false      | false       ",
    "DATADOG,B3MULTI b3 span out of range      | [DATADOG, B3MULTI]               | '1'                    | '2'                    | 'a'                    | '18446744073709551616' |                    | '1'             | '2'                | true             | false               | false      | false       ",
    "NONE                                      | [NONE]                           | '1'                    | '2'                    |                        |                        |                    |                 |                    | true             | false               | true       | false       ",
    "DATADOG,TRACECONTEXT w3c override         | [DATADOG, TRACECONTEXT]          | '1'                    | '2'                    |                        |                        | 'W3C_TRACE_PARENT' | '1'             | 'W3C_SPAN_ID_LSTR' | false            | false               | false      | false       ",
    "DATADOG,TRACECONTEXT,B3MULTI w3c override | [DATADOG, TRACECONTEXT, B3MULTI] | '1'                    | '2'                    | '1'                    | '2'                    | 'W3C_TRACE_PARENT' | '1'             | 'W3C_SPAN_ID_LSTR' | false            | false               | false      | false       ",
    "TRACECONTEXT,DATADOG                      | [TRACECONTEXT, DATADOG]          | '1'                    | '2'                    |                        |                        | 'W3C_TRACE_PARENT' | '1'             | 'W3C_SPAN_ID_LSTR' | false            | false               | false      | false       ",
    "TRACECONTEXT,B3MULTI                      | [TRACECONTEXT, B3MULTI]          |                        |                        | '1'                    | '2'                    | 'W3C_TRACE_PARENT' | '1'             | 'W3C_SPAN_ID_LSTR' | false            | false               | false      | false       ",
    "TRACECONTEXT,B3MULTI,DATADOG              | [TRACECONTEXT, B3MULTI, DATADOG] | '1'                    | '2'                    | '1'                    | '4'                    | 'W3C_TRACE_PARENT' | '1'             | 'W3C_SPAN_ID_LSTR' | false            | false               | false      | false       ",
    "B3MULTI,DATADOG,TRACECONTEXT              | [B3MULTI, DATADOG, TRACECONTEXT] | '1'                    | '2'                    | '1'                    | '4'                    | 'W3C_TRACE_PARENT' | '1'             | 'W3C_SPAN_ID_LSTR' | false            | false               | false      | false       ",
    "TRACECONTEXT only                         | [TRACECONTEXT]                   |                        |                        |                        |                        | 'W3C_TRACE_PARENT' | '1'             | 'W3C_SPAN_ID_LSTR' | false            | false               | false      | false       ",
    "DATADOG,TRACECONTEXT no dd span           | [DATADOG, TRACECONTEXT]          | '1'                    |                        |                        |                        | 'W3C_TRACE_PARENT' | '1'             | 'W3C_SPAN_ID_LSTR' | false            | false               | false      | false       ",
    "DATADOG,TRACECONTEXT extract first        | [DATADOG, TRACECONTEXT]          | '1'                    | '2'                    |                        |                        | 'W3C_TRACE_PARENT' | '1'             | '2'                | false            | false               | false      | true        "
  })
  void extractHttpHeadersUsingStyles(
      List<TracePropagationStyle> styles,
      String datadogTraceId,
      String datadogSpanId,
      String b3TraceId,
      String b3SpanId,
      @ConvertWith(W3cConstantConverter.class) String w3cTraceParent,
      String expectedTraceId,
      @ConvertWith(W3cConstantConverter.class) String expectedSpanId,
      boolean putDatadogFields,
      boolean expectDatadogFields,
      boolean tagContext,
      boolean extractFirst) {
    HttpCodec.Extractor extractor =
        createExtractor(styles, extractFirst, singletonMap("SOME_HEADER", "some-tag"));

    // spotless:off
    Map<String, String> headers = headers(
        DatadogHttpCodec.TRACE_ID_KEY, datadogTraceId,
        DatadogHttpCodec.SPAN_ID_KEY, datadogSpanId,
        B3HttpCodec.TRACE_ID_KEY, b3TraceId,
        B3HttpCodec.SPAN_ID_KEY, b3SpanId,
        W3CHttpCodec.TRACE_PARENT_KEY, w3cTraceParent,
        "SOME_HEADER", putDatadogFields ? "my-interesting-info" : null
    );
    // spotless:on

    TagContext context = extractor.extract(headers, stringValuesMap());

    if (tagContext) {
      assertInstanceOf(TagContext.class, context);
    } else {
      if (expectedTraceId == null) {
        assertNull(context);
      } else {
        assertEquals(DDTraceId.from(expectedTraceId).toLong(), context.getTraceId().toLong());
        assertEquals(DDSpanId.from(expectedSpanId), context.getSpanId());
      }
    }
    if (expectDatadogFields) {
      Map<String, String> expectedTags = new LinkedHashMap<>();
      if (tagContext && b3TraceId != null) {
        expectedTags.put(B3_TRACE_ID, b3TraceId);
        expectedTags.put(B3_SPAN_ID, b3SpanId);
      }
      expectedTags.put("some-tag", "my-interesting-info");
      assertNotNull(context);
      assertEquals(expectedTags, context.getTags());
    }
  }

  @TableTest({
    "scenario                                       | styles                           | datadogTraceId | datadogSpanId | b3TraceId | b3SpanId | traceState               | expectedTraceId | expectedSpanId     | expectedParentId  ",
    "DATADOG,TRACECONTEXT with traceState p         | [DATADOG, TRACECONTEXT]          | '1'            | '2'           |           |          | 'W3C_TRACE_STATE_WITH_P' | '1'             | 'W3C_SPAN_ID_LSTR' | 'W3C_PARENT_ID'   ",
    "DATADOG,TRACECONTEXT no traceState             | [DATADOG, TRACECONTEXT]          | '1'            | '2'           |           |          |                          | '1'             | 'W3C_SPAN_ID_LSTR' | '0000000000000002'",
    "DATADOG,TRACECONTEXT,B3MULTI with traceState p | [DATADOG, TRACECONTEXT, B3MULTI] | '1'            | '2'           | '1'       | '2'      | 'W3C_TRACE_STATE_WITH_P' | '1'             | 'W3C_SPAN_ID_LSTR' | 'W3C_PARENT_ID'   ",
    "TRACECONTEXT,DATADOG with traceState p         | [TRACECONTEXT, DATADOG]          | '1'            | '2'           |           |          | 'W3C_TRACE_STATE_WITH_P' | '1'             | 'W3C_SPAN_ID_LSTR' |                   ",
    "TRACECONTEXT,B3MULTI with traceState p         | [TRACECONTEXT, B3MULTI]          |                |               | '1'       | '2'      | 'W3C_TRACE_STATE_WITH_P' | '1'             | 'W3C_SPAN_ID_LSTR' |                   ",
    "TRACECONTEXT,B3MULTI,DATADOG with traceState p | [TRACECONTEXT, B3MULTI, DATADOG] | '1'            | '2'           | '1'       | '4'      | 'W3C_TRACE_STATE_WITH_P' | '1'             | 'W3C_SPAN_ID_LSTR' |                   ",
    "B3MULTI,DATADOG,TRACECONTEXT with traceState p | [B3MULTI, DATADOG, TRACECONTEXT] | '1'            | '2'           | '1'       | '4'      | 'W3C_TRACE_STATE_WITH_P' | '1'             | 'W3C_SPAN_ID_LSTR' | 'W3C_PARENT_ID'   ",
    "TRACECONTEXT only with traceState p            | [TRACECONTEXT]                   |                |               |           |          | 'W3C_TRACE_STATE_WITH_P' | '1'             | 'W3C_SPAN_ID_LSTR' |                   ",
    "B3MULTI,TRACECONTEXT with traceState p         | [B3MULTI, TRACECONTEXT]          |                |               | '1'       | '2'      | 'W3C_TRACE_STATE_WITH_P' | '1'             | 'W3C_SPAN_ID_LSTR' | 'W3C_PARENT_ID'   ",
    "B3MULTI,DATADOG,TRACECONTEXT no traceState     | [B3MULTI, DATADOG, TRACECONTEXT] | '1'            | '2'           | '1'       | '4'      |                          | '1'             | 'W3C_SPAN_ID_LSTR' | '0000000000000002'",
    "DATADOG,TRACECONTEXT no p traceState           | [DATADOG, TRACECONTEXT]          | '1'            | '2'           |           |          | 'W3C_TRACE_STATE_NO_P'   | '1'             | 'W3C_SPAN_ID_LSTR' | '0000000000000002'"
  })
  void checkW3CTraceContextOverride(
      List<TracePropagationStyle> styles,
      String datadogTraceId,
      String datadogSpanId,
      String b3TraceId,
      String b3SpanId,
      @ConvertWith(W3cConstantConverter.class) String traceState,
      String expectedTraceId,
      @ConvertWith(W3cConstantConverter.class) String expectedSpanId,
      @ConvertWith(W3cConstantConverter.class) String expectedParentId) {
    HttpCodec.Extractor extractor = createExtractor(styles);

    // spotless:off
    Map<String, String> headers = headers(
        W3CHttpCodec.TRACE_PARENT_KEY, W3C_TRACE_PARENT,
        DatadogHttpCodec.TRACE_ID_KEY, datadogTraceId,
        DatadogHttpCodec.SPAN_ID_KEY, datadogSpanId,
        B3HttpCodec.TRACE_ID_KEY, b3TraceId,
        B3HttpCodec.SPAN_ID_KEY, b3SpanId,
        W3CHttpCodec.TRACE_STATE_KEY, traceState
    );
    // spotless:on

    TagContext context = extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.from(expectedTraceId).toLong(), context.getTraceId().toLong());
    assertEquals(DDSpanId.from(expectedSpanId), context.getSpanId());
    assertEquals(expectedParentId, context.getTags().getString(PARENT_ID));
    // TODO Add some more W3C override checks
  }

  @TableTest({
    "scenario                           | styles                           | datadogTraceId | datadogSpanId | b3TraceId | b3SpanId | w3cTraceParent     | traceState             | expectedSpanLinks      ",
    "matching trace IDs no links        | [DATADOG, B3MULTI, TRACECONTEXT] | '1'            | '2'           | '1'       | 'b'      | 'W3C_TRACE_PARENT' | 'W3C_TRACE_STATE_NO_P' | []                     ",
    "only tracecontext mismatch         | [DATADOG, B3MULTI, TRACECONTEXT] | '2'            | '2'           | '2'       | 'b'      | 'W3C_TRACE_PARENT' | 'W3C_TRACE_STATE_NO_P' | [TRACECONTEXT]         ",
    "b3 and tracecontext mismatch       | [DATADOG, B3MULTI, TRACECONTEXT] | '2'            | '2'           | '1'       | 'b'      | 'W3C_TRACE_PARENT' | 'W3C_TRACE_STATE_NO_P' | [B3MULTI, TRACECONTEXT]",
    "datadog mismatch from tracecontext | [TRACECONTEXT, B3MULTI, DATADOG] | '2'            | '2'           | '1'       | 'b'      | 'W3C_TRACE_PARENT' | 'W3C_TRACE_STATE_NO_P' | [DATADOG]              "
  })
  void verifyExistenceOfSpanLinks(
      List<TracePropagationStyle> styles,
      String datadogTraceId,
      String datadogSpanId,
      String b3TraceId,
      String b3SpanId,
      @ConvertWith(W3cConstantConverter.class) String w3cTraceParent,
      @ConvertWith(W3cConstantConverter.class) String traceState,
      List<TracePropagationStyle> expectedSpanLinks) {
    HttpCodec.Extractor extractor = createExtractor(styles);

    // spotless:off
    Map<String, String> headers = headers(
        DatadogHttpCodec.TRACE_ID_KEY, datadogTraceId,
        DatadogHttpCodec.SPAN_ID_KEY, datadogSpanId,
        B3HttpCodec.TRACE_ID_KEY, b3TraceId,
        B3HttpCodec.SPAN_ID_KEY, b3SpanId,
        W3CHttpCodec.TRACE_PARENT_KEY, w3cTraceParent,
        W3CHttpCodec.TRACE_STATE_KEY, traceState
    );
    // spotless:on

    TagContext context = extractor.extract(headers, stringValuesMap());

    List<AgentSpanLink> links = context.getTerminatedSpanLinks();
    assertEquals(expectedSpanLinks.size(), links.size());
    for (int i = 0; i < links.size(); i++) {
      TracePropagationStyle style = expectedSpanLinks.get(i);
      if (style == TRACECONTEXT) {
        assertEquals(W3C_TRACE_STATE_NO_P, links.get(i).traceState());
      }
      assertEquals(style.toString(), links.get(i).attributes().asMap().get("context_headers"));
    }
  }

  private static Set<TracePropagationStyle> orderedSetOf(List<TracePropagationStyle> styles) {
    return new LinkedHashSet<>(styles);
  }

  private static HttpCodec.Extractor createExtractor(List<TracePropagationStyle> styles) {
    return createExtractor(styles, false, emptyMap());
  }

  private static HttpCodec.Extractor createExtractor(
      List<TracePropagationStyle> styles, boolean extractFirst, Map<String, String> headerTags) {
    Config config = mock(Config.class);
    when(config.getTracePropagationStylesToExtract()).thenReturn(orderedSetOf(styles));
    when(config.isTracePropagationExtractFirst()).thenReturn(extractFirst);
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create().setHeaderTags(headerTags).setBaggageMapping(emptyMap()).apply();
    return HttpCodec.createExtractor(config, dynamicConfig::captureTraceConfig);
  }

  @TypeConverter
  static TracePropagationStyle parseTracePropagationStyle(String value) {
    String name = value.trim();
    if (name.startsWith("TracePropagationStyle.")) {
      name = name.substring("TracePropagationStyle.".length());
    }
    return TracePropagationStyle.valueOf(name);
  }

  static class W3cConstantConverter implements ArgumentConverter {
    @Override
    public Object convert(Object source, ParameterContext context)
        throws ArgumentConversionException {
      if (source == null) {
        return null;
      }
      switch (source.toString()) {
        case "W3C_TRACE_PARENT":
          return W3C_TRACE_PARENT;
        case "W3C_TRACE_STATE_WITH_P":
          return W3C_TRACE_STATE_WITH_P;
        case "W3C_TRACE_STATE_NO_P":
          return W3C_TRACE_STATE_NO_P;
        case "W3C_SPAN_ID_LSTR":
          return W3C_SPAN_ID_LSTR;
        case "W3C_PARENT_ID":
          return W3C_PARENT_ID;
        default:
          return source;
      }
    }
  }
}
