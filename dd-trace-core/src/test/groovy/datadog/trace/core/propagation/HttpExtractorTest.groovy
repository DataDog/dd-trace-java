package datadog.trace.core.propagation

import static datadog.trace.api.DDTags.PARENT_ID
import static datadog.trace.api.TracePropagationStyle.NONE

import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.DynamicConfig
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.api.TracePropagationStyle.B3MULTI
import static datadog.trace.api.TracePropagationStyle.DATADOG
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX

class HttpExtractorTest extends DDSpecification {
  static final W3C_TRACE_ID = "00000000000000000000000000000001"
  static final W3C_SPAN_ID = "123456789abcdef0"
  static final W3C_TRACE_PARENT = "00-$W3C_TRACE_ID-$W3C_SPAN_ID-01"
  static final W3C_TRACE_STATE_WITH_P = "dd=p:456789abcdef0123"
  static final W3C_TRACE_STATE_NO_P = "dd=s:2,foo=1"
  static final W3C_SPAN_ID_LSTR = DDSpanId.fromHex(W3C_SPAN_ID).toString()

  @Shared
  String outOfRangeTraceId = (TRACE_ID_MAX + 1).toString()

  def "extract http headers using #styles"() {
    setup:
    Config config = Mock(Config) {
      getTracePropagationStylesToExtract() >> styles
      isTracePropagationExtractFirst() >> extractFirst
    }
    DynamicConfig dynamicConfig = DynamicConfig.create()
      .setHeaderTags(["SOME_HEADER": "some-tag"])
      .setBaggageMapping([:])
      .apply()
    HttpCodec.Extractor extractor = HttpCodec.createExtractor(config, { dynamicConfig.captureTraceConfig() })

    final Map<String, String> actual = [:]
    if (datadogTraceId != null) {
      actual.put(DatadogHttpCodec.TRACE_ID_KEY.toUpperCase(), datadogTraceId)
    }
    if (datadogSpanId != null) {
      actual.put(DatadogHttpCodec.SPAN_ID_KEY.toUpperCase(), datadogSpanId)
    }
    if (b3TraceId != null) {
      actual.put(B3HttpCodec.TRACE_ID_KEY.toUpperCase(), b3TraceId)
    }
    if (b3SpanId != null) {
      actual.put(B3HttpCodec.SPAN_ID_KEY.toUpperCase(), b3SpanId)
    }
    if (w3cTraceParent != null) {
      actual.put(W3CHttpCodec.TRACE_PARENT_KEY.toUpperCase(), w3cTraceParent)
    }

    if (putDatadogFields) {
      actual.put("SOME_HEADER", "my-interesting-info")
    }

    when:
    final TagContext context = extractor.extract(actual, ContextVisitors.stringValuesMap())

    then:
    if (tagContext) {
      assert context instanceof TagContext
    } else {
      if (expectedTraceId == null) {
        assert context == null
      } else {
        assert context.traceId.toLong() == DDTraceId.from(expectedTraceId).toLong()
        assert context.spanId == DDSpanId.from(expectedSpanId)
      }
    }

    if (expectDatadogFields) {
      if (tagContext && b3TraceId != null) {
        assert context.tags == ["b3.traceid": b3TraceId, "b3.spanid": b3SpanId, "some-tag": "my-interesting-info"]
      } else {
        assert context.tags == ["some-tag": "my-interesting-info"]
      }
    }

    where:
    // spotless:off
    styles                           | datadogTraceId    | datadogSpanId     | b3TraceId         | b3SpanId          | w3cTraceParent   | expectedTraceId | expectedSpanId   | putDatadogFields | expectDatadogFields | tagContext | extractFirst
    [DATADOG, B3MULTI]               | "1"               | "2"               | "a"               | "b"               | null             | "1"             | "2"              | true             | true                | false      | false
    [DATADOG, B3MULTI]               | null              | null              | "a"               | "b"               | null             | "10"            | "11"             | false            | false               | true       | false
    [DATADOG, B3MULTI]               | null              | null              | "a"               | "b"               | null             | null            | null             | true             | true                | true       | false
    [DATADOG]                        | "1"               | "2"               | "a"               | "b"               | null             | "1"             | "2"              | true             | true                | false      | false
    [B3MULTI]                        | "1"               | "2"               | "a"               | "b"               | null             | "10"            | "11"             | false            | false               | false      | false
    [B3MULTI, DATADOG]               | "1"               | "2"               | "a"               | "b"               | null             | "10"            | "11"             | false            | false               | false      | false
    []                               | "1"               | "2"               | "a"               | "b"               | null             | null            | null             | false            | false               | false      | false
    [DATADOG, B3MULTI]               | "abc"             | "2"               | "a"               | "b"               | null             | "10"            | "11"             | false            | false               | false      | false
    [DATADOG]                        | "abc"             | "2"               | "a"               | "b"               | null             | null            | null             | false            | false               | false      | false
    [DATADOG, B3MULTI]               | outOfRangeTraceId | "2"               | "a"               | "b"               | null             | "10"            | "11"             | false            | false               | false      | false
    [DATADOG, B3MULTI]               | "1"               | outOfRangeTraceId | "a"               | "b"               | null             | "10"            | "11"             | false            | false               | false      | false
    [DATADOG]                        | outOfRangeTraceId | "2"               | "a"               | "b"               | null             | null            | null             | false            | false               | false      | false
    [DATADOG]                        | "1"               | outOfRangeTraceId | "a"               | "b"               | null             | null            | null             | false            | false               | false      | false
    [DATADOG, B3MULTI]               | "1"               | "2"               | outOfRangeTraceId | "b"               | null             | "1"             | "2"              | true             | false               | false      | false
    [DATADOG, B3MULTI]               | "1"               | "2"               | "a"               | outOfRangeTraceId | null             | "1"             | "2"              | true             | false               | false      | false
    [NONE]                           | "1"               | "2"               | null              | null              | null             | null            | null             | true             | false               | true       | false
    [DATADOG, TRACECONTEXT]          | "1"               | "2"               | null              | null              | W3C_TRACE_PARENT | "1"             | W3C_SPAN_ID_LSTR | false            | false               | false      | false
    [DATADOG, TRACECONTEXT, B3MULTI] | "1"               | "2"               | "1"               | "2"               | W3C_TRACE_PARENT | "1"             | W3C_SPAN_ID_LSTR | false            | false               | false      | false
    [TRACECONTEXT, DATADOG]          | "1"               | "2"               | null              | null              | W3C_TRACE_PARENT | "1"             | W3C_SPAN_ID_LSTR | false            | false               | false      | false
    [TRACECONTEXT, B3MULTI]          | null              | null              | "1"               | "2"               | W3C_TRACE_PARENT | "1"             | W3C_SPAN_ID_LSTR | false            | false               | false      | false
    [TRACECONTEXT, B3MULTI, DATADOG] | "1"               | "2"               | "1"               | "4"               | W3C_TRACE_PARENT | "1"             | W3C_SPAN_ID_LSTR | false            | false               | false      | false
    [B3MULTI, DATADOG, TRACECONTEXT] | "1"               | "2"               | "1"               | "4"               | W3C_TRACE_PARENT | "1"             | W3C_SPAN_ID_LSTR | false            | false               | false      | false
    [TRACECONTEXT]                   | null              | null              | null              | null              | W3C_TRACE_PARENT | "1"             | W3C_SPAN_ID_LSTR | false            | false               | false      | false
    [DATADOG, TRACECONTEXT]          | "1"               | null              | null              | null              | W3C_TRACE_PARENT | "1"             | W3C_SPAN_ID_LSTR | false            | false               | false      | false
    [DATADOG, TRACECONTEXT]          | "1"               | "2"               | null              | null              | W3C_TRACE_PARENT | "1"             | "2"              | false            | false               | false      | true
    // spotless:on
  }

  def 'check W3C trace context override'() {
    setup:
    Config config = Mock(Config) {
      getTracePropagationStylesToExtract() >> styles
    }
    DynamicConfig dynamicConfig = DynamicConfig.create().apply()
    HttpCodec.Extractor extractor = HttpCodec.createExtractor(config, { dynamicConfig.captureTraceConfig() })

    final Map<String, String> actual = [:]
    actual.put(W3CHttpCodec.TRACE_PARENT_KEY.toUpperCase(), W3C_TRACE_PARENT)
    if (datadogTraceId != null) {
      actual.put(DatadogHttpCodec.TRACE_ID_KEY.toUpperCase(), datadogTraceId)
    }
    if (datadogSpanId != null) {
      actual.put(DatadogHttpCodec.SPAN_ID_KEY.toUpperCase(), datadogSpanId)
    }
    if (b3TraceId != null) {
      actual.put(B3HttpCodec.TRACE_ID_KEY.toUpperCase(), b3TraceId)
    }
    if (b3SpanId != null) {
      actual.put(B3HttpCodec.SPAN_ID_KEY.toUpperCase(), b3SpanId)
    }
    if (traceState != null) {
      actual.put(W3CHttpCodec.TRACE_STATE_KEY.toUpperCase(), traceState)
    }

    when:
    final TagContext context = extractor.extract(actual, ContextVisitors.stringValuesMap())

    then:
    assert context.traceId.toLong() == DDTraceId.from(expectedTraceId).toLong()
    assert context.spanId == DDSpanId.from(expectedSpanId)
    assert context.tags[PARENT_ID] == expectedParentId
    // TODO Add some more W3C override checks

    where:
    // spotless:off
    styles                           | datadogTraceId | datadogSpanId | b3TraceId | b3SpanId | traceState             | expectedTraceId | expectedSpanId   | expectedParentId
    [DATADOG, TRACECONTEXT]          | "1"            | "2"           | null      | null     | W3C_TRACE_STATE_WITH_P | "1"             | W3C_SPAN_ID_LSTR | "456789abcdef0123"
    [DATADOG, TRACECONTEXT]          | "1"            | "2"           | null      | null     | null                   | "1"             | W3C_SPAN_ID_LSTR | "0000000000000002"
    [DATADOG, TRACECONTEXT, B3MULTI] | "1"            | "2"           | "1"       | "2"      | W3C_TRACE_STATE_WITH_P | "1"             | W3C_SPAN_ID_LSTR | "456789abcdef0123"
    [TRACECONTEXT, DATADOG]          | "1"            | "2"           | null      | null     | W3C_TRACE_STATE_WITH_P | "1"             | W3C_SPAN_ID_LSTR | null
    [TRACECONTEXT, B3MULTI]          | null           | null          | "1"       | "2"      | W3C_TRACE_STATE_WITH_P | "1"             | W3C_SPAN_ID_LSTR | null
    [TRACECONTEXT, B3MULTI, DATADOG] | "1"            | "2"           | "1"       | "4"      | W3C_TRACE_STATE_WITH_P | "1"             | W3C_SPAN_ID_LSTR | null
    [B3MULTI, DATADOG, TRACECONTEXT] | "1"            | "2"           | "1"       | "4"      | W3C_TRACE_STATE_WITH_P | "1"             | W3C_SPAN_ID_LSTR | "456789abcdef0123"
    [TRACECONTEXT]                   | null           | null          | null      | null     | W3C_TRACE_STATE_WITH_P | "1"             | W3C_SPAN_ID_LSTR | null
    [B3MULTI, TRACECONTEXT]          | null           | null          | "1"       | "2"      | W3C_TRACE_STATE_WITH_P | "1"             | W3C_SPAN_ID_LSTR | "456789abcdef0123"
    [B3MULTI, DATADOG, TRACECONTEXT] | "1"            | "2"           | "1"       | "4"      | null                   | "1"             | W3C_SPAN_ID_LSTR | "0000000000000002"
    [DATADOG, TRACECONTEXT]          | "1"            | "2"           | null      | null     | W3C_TRACE_STATE_NO_P   | "1"             | W3C_SPAN_ID_LSTR | "0000000000000002"
    // spotless:on
  }

  def "verify existence of span links when extracting compound http headers"() {
    setup:
    Config config = Mock(Config) {
      getTracePropagationStylesToExtract() >> styles
    }
    DynamicConfig dynamicConfig = DynamicConfig.create().apply()
    HttpCodec.Extractor extractor = HttpCodec.createExtractor(config, { dynamicConfig.captureTraceConfig() })

    final Map<String, String> actual = [
      (DatadogHttpCodec.TRACE_ID_KEY.toUpperCase()): datadogTraceId,
      (DatadogHttpCodec.SPAN_ID_KEY.toUpperCase()) : datadogSpanId,
      (B3HttpCodec.TRACE_ID_KEY.toUpperCase())     : b3TraceId,
      (B3HttpCodec.SPAN_ID_KEY.toUpperCase())      : b3SpanId,
      (W3CHttpCodec.TRACE_PARENT_KEY.toUpperCase()): w3cTraceParent,
      (W3CHttpCodec.TRACE_STATE_KEY.toUpperCase()) : traceState
    ]

    when:
    final TagContext context = extractor.extract(actual, ContextVisitors.stringValuesMap())

    then:
    def links = context.getTerminatedContextLinks()
    assert links.size() == expectedSpanLinks.size()
    for (int i = 0; i < links.size(); i++) {
      if (expectedSpanLinks[i] == TRACECONTEXT) {
        assert links[i].traceState() == W3C_TRACE_STATE_NO_P
      }
      assert links[i].attributes().asMap()["context_headers"] == expectedSpanLinks[i].toString()
    }

    where:
    // spotless:off
    styles                           | datadogTraceId    | datadogSpanId     | b3TraceId         | b3SpanId          | w3cTraceParent   | traceState           | expectedSpanLinks
    [DATADOG, B3MULTI, TRACECONTEXT] | "1"               | "2"               | "1"               | "b"               | W3C_TRACE_PARENT | W3C_TRACE_STATE_NO_P | []
    [DATADOG, B3MULTI, TRACECONTEXT] | "2"               | "2"               | "2"               | "b"               | W3C_TRACE_PARENT | W3C_TRACE_STATE_NO_P | [TRACECONTEXT]
    [DATADOG, B3MULTI, TRACECONTEXT] | "2"               | "2"               | "1"               | "b"               | W3C_TRACE_PARENT | W3C_TRACE_STATE_NO_P | [B3MULTI, TRACECONTEXT]
    [TRACECONTEXT, B3MULTI, DATADOG] | "2"               | "2"               | "1"               | "b"               | W3C_TRACE_PARENT | W3C_TRACE_STATE_NO_P | [DATADOG]
    // spotless:on
  }
}
