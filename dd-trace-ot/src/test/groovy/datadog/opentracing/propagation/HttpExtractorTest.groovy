package datadog.opentracing.propagation

import datadog.trace.api.Config
import datadog.trace.util.test.DDSpecification
import io.opentracing.SpanContext
import io.opentracing.propagation.TextMapExtractAdapter
import spock.lang.Shared

import static datadog.opentracing.DDTracer.TRACE_ID_MAX
import static datadog.trace.api.Config.PropagationStyle.B3
import static datadog.trace.api.Config.PropagationStyle.DATADOG

class HttpExtractorTest extends DDSpecification {

  @Shared
  String outOfRangeTraceId = (TRACE_ID_MAX + 1).toString()

  def "extract http headers"() {
    setup:
    Config config = Mock(Config) {
      getPropagationStylesToExtract() >> styles
    }
    HttpCodec.Extractor extractor = HttpCodec.createExtractor(config, ["SOME_HEADER": "some-tag"])

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

    if (putDatadogFields) {
      actual.put("SOME_HEADER", "my-interesting-info")
    }

    when:
    final SpanContext context = extractor.extract(new TextMapExtractAdapter(actual))

    then:
    if (tagContext) {
      assert context instanceof TagContext
    } else {
      if (expectedTraceId == null) {
        assert context == null
      } else {
        assert context.traceId == expectedTraceId
        assert context.spanId == expectedSpanId
      }
    }

    if (expectDatadogFields) {
      assert context.tags == ["some-tag": "my-interesting-info"]
    }

    where:
    styles        | datadogTraceId    | datadogSpanId     | b3TraceId         | b3SpanId          | expectedTraceId | expectedSpanId | putDatadogFields | expectDatadogFields | tagContext
    [DATADOG, B3] | "1"               | "2"               | "a"               | "b"               | 1G              | 2G             | true             | true                | false
    [DATADOG, B3] | null              | null              | "a"               | "b"               | 10G             | 11G            | false            | false               | true
    [DATADOG, B3] | null              | null              | "a"               | "b"               | null            | null           | true             | true                | true
    [DATADOG]     | "1"               | "2"               | "a"               | "b"               | 1G              | 2G             | true             | true                | false
    [B3]          | "1"               | "2"               | "a"               | "b"               | 10G             | 11G            | false            | false               | false
    [B3, DATADOG] | "1"               | "2"               | "a"               | "b"               | 10G             | 11G            | false            | false               | false
    []            | "1"               | "2"               | "a"               | "b"               | null            | null           | false            | false               | false
    [DATADOG, B3] | "abc"             | "2"               | "a"               | "b"               | 10G             | 11G            | false            | false               | false
    [DATADOG]     | "abc"             | "2"               | "a"               | "b"               | null            | null           | false            | false               | false
    [DATADOG, B3] | outOfRangeTraceId | "2"               | "a"               | "b"               | 10G             | 11G            | false            | false               | false
    [DATADOG, B3] | "1"               | outOfRangeTraceId | "a"               | "b"               | 10G             | 11G            | false            | false               | false
    [DATADOG]     | outOfRangeTraceId | "2"               | "a"               | "b"               | null            | null           | false            | false               | false
    [DATADOG]     | "1"               | outOfRangeTraceId | "a"               | "b"               | null            | null           | false            | false               | false
    [DATADOG, B3] | "1"               | "2"               | outOfRangeTraceId | "b"               | 1G              | 2G             | true             | false               | false
    [DATADOG, B3] | "1"               | "2"               | "a"               | outOfRangeTraceId | 1G              | 2G             | true             | false               | false
  }

}
