package datadog.trace.core.propagation

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.util.test.DDSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY

class DatadogHttpExtractorTest extends DDSpecification {

  HttpCodec.Extractor extractor = DatadogHttpCodec.newExtractor(["SOME_HEADER": "some-tag"])

  def "extract http headers"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : traceId,
      (SPAN_ID_KEY.toUpperCase())             : spanId,
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    if (samplingPriority != PrioritySampling.UNSET) {
      headers.put(SAMPLING_PRIORITY_KEY, "$samplingPriority".toString())
    }

    if (origin) {
      headers.put(ORIGIN_KEY, origin)
    }

    when:
    final ExtractedContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    context.traceId == DDId.from(traceId)
    context.spanId == DDId.from(spanId)
    context.baggage == ["k1": "v1", "k2": "v2"]
    context.tags == ["some-tag": "my-interesting-info"]
    context.samplingPriority == samplingPriority
    context.origin == origin

    where:
    traceId               | spanId                | samplingPriority              | origin
    "1"                   | "2"                   | PrioritySampling.UNSET        | null
    "2"                   | "3"                   | PrioritySampling.SAMPLER_KEEP | "saipan"
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | PrioritySampling.UNSET        | "saipan"
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | PrioritySampling.SAMPLER_KEEP | "saipan"
  }

  def "extract header tags with no propagation"() {
    when:
    TagContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    !(context instanceof ExtractedContext)
    context.getTags() == ["some-tag": "my-interesting-info"]
    if (headers.containsKey(ORIGIN_KEY)) {
      assert ((TagContext) context).origin == "my-origin"
    }

    where:
    headers                                                         | _
    [SOME_HEADER: "my-interesting-info"]                            | _
    [(ORIGIN_KEY): "my-origin", SOME_HEADER: "my-interesting-info"] | _
  }

  def "extract empty headers returns null"() {
    expect:
    extractor.extract(["ignored-header": "ignored-value"], MapGetter.INSTANCE) == null
  }

  def "extract http headers with invalid non-numeric ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : "traceId",
      (SPAN_ID_KEY.toUpperCase())             : "spanId",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    TagContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    context == null
  }

  def "extract http headers with out of range trace ID"() {
    setup:
    String outOfRangeTraceId = (TRACE_ID_MAX + 1).toString()
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : outOfRangeTraceId,
      (SPAN_ID_KEY.toUpperCase())             : "0",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    TagContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    context == null
  }

  def "extract http headers with out of range span ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : "0",
      (SPAN_ID_KEY.toUpperCase())             : "-1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    TagContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    context == null
  }

  def "more ID range validation"() {
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
    traceId               | spanId                | expectedTraceId | expectedSpanId
    "-1"                  | "1"                   | null            | null
    "1"                   | "-1"                  | null            | null
    "0"                   | "1"                   | null            | null
    "1"                   | "0"                   | DDId.ONE        | DDId.ZERO
    "$TRACE_ID_MAX"       | "1"                   | DDId.MAX        | DDId.ONE
    "${TRACE_ID_MAX + 1}" | "1"                   | null            | null
    "1"                   | "$TRACE_ID_MAX"       | DDId.ONE        | DDId.MAX
    "1"                   | "${TRACE_ID_MAX + 1}" | null            | null
  }
}
