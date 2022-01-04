package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.api.PropagationStyle.B3
import static datadog.trace.api.PropagationStyle.DATADOG
import static datadog.trace.core.propagation.DatadogHttpCodec.*
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX

class HttpExtractorTest extends DDSpecification {

  @Shared
  String outOfRangeTraceId = (TRACE_ID_MAX + 1).toString()

  @Shared
  String ddTags1 = "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1"

  def "extract http headers"() {
    setup:
    Config config = Mock(Config) {
      getPropagationStylesToExtract() >> styles
    }
    HttpCodec.Extractor extractor = HttpCodec.createExtractor(config, ["SOME_HEADER": "some-tag"])

    final Map<String, String> actual = [:]
    if (datadogTraceId != null) {
      actual.put(TRACE_ID_KEY.toUpperCase(), datadogTraceId)
    }
    if (datadogSpanId != null) {
      actual.put(SPAN_ID_KEY.toUpperCase(), datadogSpanId)
    }
    if (b3TraceId != null) {
      actual.put(B3HttpCodec.TRACE_ID_KEY.toUpperCase(), b3TraceId)
    }
    if (b3SpanId != null) {
      actual.put(B3HttpCodec.SPAN_ID_KEY.toUpperCase(), b3SpanId)
    }

    if (putDatadogFields) {
      actual.put("SOME_HEADER", "my-interesting-info")
    }

    if (ddTags != null) {
      actual.put(TAGS_KEY.toUpperCase(), ddTags)
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
        assert context.traceId == DDId.from(expectedTraceId)
        assert context.spanId == DDId.from(expectedSpanId)
      }
    }

    if (expectDatadogFields) {
      if (tagContext) {
        assert context.tags == ["b3.traceid": b3TraceId, "b3.spanid": b3SpanId, "some-tag": "my-interesting-info"]
      } else {
        assert context.tags == ["some-tag": "my-interesting-info"]
      }
    }

    if (expectedDdTags != null) {
      assert context instanceof ExtractedContext && context.ddTags.encoded() == expectedDdTags
    }

    where:
    // spotless:off
    styles        | datadogTraceId    | datadogSpanId     | b3TraceId         | b3SpanId          | expectedTraceId | expectedSpanId | putDatadogFields | expectDatadogFields | tagContext | ddTags  | expectedDdTags
    [DATADOG, B3] | "1"               | "2"               | "a"               | "b"               | "1"             | "2"            | true             | true                | false      | ddTags1 | ddTags1
    [DATADOG, B3] | null              | null              | "a"               | "b"               | "10"            | "11"           | false            | false               | true       | ddTags1 | null
    [DATADOG, B3] | null              | null              | "a"               | "b"               | null            | null           | true             | true                | true       | ddTags1 | null
    [DATADOG]     | "1"               | "2"               | "a"               | "b"               | "1"             | "2"            | true             | true                | false      | ddTags1 | ddTags1
    [B3]          | "1"               | "2"               | "a"               | "b"               | "10"            | "11"           | false            | false               | false      | ddTags1 | null
    [B3, DATADOG] | "1"               | "2"               | "a"               | "b"               | "10"            | "11"           | false            | false               | false      | ddTags1 | null
    []            | "1"               | "2"               | "a"               | "b"               | null            | null           | false            | false               | false      | ddTags1 | null
    [DATADOG, B3] | "abc"             | "2"               | "a"               | "b"               | "10"            | "11"           | false            | false               | false      | ddTags1 | null
    [DATADOG]     | "abc"             | "2"               | "a"               | "b"               | null            | null           | false            | false               | false      | ddTags1 | null
    [DATADOG, B3] | outOfRangeTraceId | "2"               | "a"               | "b"               | "10"            | "11"           | false            | false               | false      | ddTags1 | null
    [DATADOG, B3] | "1"               | outOfRangeTraceId | "a"               | "b"               | "10"            | "11"           | false            | false               | false      | ddTags1 | null
    [DATADOG]     | outOfRangeTraceId | "2"               | "a"               | "b"               | null            | null           | false            | false               | false      | ddTags1 | null
    [DATADOG]     | "1"               | outOfRangeTraceId | "a"               | "b"               | null            | null           | false            | false               | false      | ddTags1 | null
    [DATADOG, B3] | "1"               | "2"               | outOfRangeTraceId | "b"               | "1"             | "2"            | true             | false               | false      | ddTags1 | ddTags1
    [DATADOG, B3] | "1"               | "2"               | "a"               | outOfRangeTraceId | "1"             | "2"            | true             | false               | false      | ddTags1 | ddTags1
    // spotless:on
  }
}
