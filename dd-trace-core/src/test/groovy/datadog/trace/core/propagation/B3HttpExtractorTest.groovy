package datadog.trace.core.propagation

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.util.test.DDSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY

class B3HttpExtractorTest extends DDSpecification {

  HttpCodec.Extractor extractor = B3HttpCodec.newExtractor(["SOME_HEADER": "some-tag"])

  def "extract http headers"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): traceId.toString(16).toLowerCase(),
      (SPAN_ID_KEY.toUpperCase()) : spanId.toString(16).toLowerCase(),
      SOME_HEADER                 : "my-interesting-info",
    ]

    if (samplingPriority != null) {
      headers.put(SAMPLING_PRIORITY_KEY, "$samplingPriority".toString())
    }

    when:
    final ExtractedContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    context.traceId == DDId.from("$traceId")
    context.spanId == DDId.from("$spanId")
    context.baggage == [:]
    context.tags == ["some-tag": "my-interesting-info"]
    context.samplingPriority == expectedSamplingPriority
    context.origin == null

    where:
    traceId          | spanId           | samplingPriority | expectedSamplingPriority
    1G               | 2G               | null             | PrioritySampling.UNSET
    2G               | 3G               | 1                | PrioritySampling.SAMPLER_KEEP
    3G               | 4G               | 0                | PrioritySampling.SAMPLER_DROP
    TRACE_ID_MAX     | TRACE_ID_MAX - 1 | 0                | PrioritySampling.SAMPLER_DROP
    TRACE_ID_MAX - 1 | TRACE_ID_MAX     | 1                | PrioritySampling.SAMPLER_KEEP
  }

  def "extract 128 bit id truncates id to 64 bit"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): traceId,
      (SPAN_ID_KEY.toUpperCase()) : spanId,
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    if (expectedTraceId) {
      assert context.traceId == expectedTraceId
      assert context.spanId == expectedSpanId
    } else {
      assert context == null
    }

    where:
    traceId                            | spanId             | expectedTraceId                  | expectedSpanId
    "-1"                               | "1"                | null                             | null
    "1"                                | "-1"               | null                             | null
    "0"                                | "1"                | null                             | null
    "00001"                            | "00001"            | DDId.ONE                         | DDId.ONE
    "463ac35c9f6413ad"                 | "463ac35c9f6413ad" | DDId.from("5060571933882717101") | DDId.from("5060571933882717101")
    "463ac35c9f6413ad48485a3953bb6124" | "1"                | DDId.from("5208512171318403364") | DDId.ONE
    "f" * 16                           | "1"                | DDId.MAX                         | DDId.ONE
    "a" * 16 + "f" * 16                | "1"                | DDId.MAX                         | DDId.ONE
    "1" + "f" * 32                     | "1"                | null                             | null
    "0" + "f" * 32                     | "1"                | null                             | null
    "1"                                | "f" * 16           | DDId.ONE                         | DDId.MAX
    "1"                                | "1" + "f" * 16     | null                             | null
    "1"                                | "000" + "f" * 16   | DDId.ONE                         | DDId.MAX
  }

  def "extract header tags with no propagation"() {
    when:
    TagContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    !(context instanceof ExtractedContext)
    context.getTags() == ["some-tag": "my-interesting-info"]

    where:
    headers                              | _
    [SOME_HEADER: "my-interesting-info"] | _
  }

  def "extract empty headers returns null"() {
    expect:
    extractor.extract(["ignored-header": "ignored-value"], MapGetter.INSTANCE) == null
  }

  def "extract http headers with invalid non-numeric ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): "traceId",
      (SPAN_ID_KEY.toUpperCase()) : "spanId",
      SOME_HEADER                 : "my-interesting-info",
    ]

    when:
    TagContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    context == null
  }

  def "extract http headers with out of range span ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): "0",
      (SPAN_ID_KEY.toUpperCase()) : "-1",
      SOME_HEADER                 : "my-interesting-info",
    ]


    when:
    TagContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    context == null
  }
}
