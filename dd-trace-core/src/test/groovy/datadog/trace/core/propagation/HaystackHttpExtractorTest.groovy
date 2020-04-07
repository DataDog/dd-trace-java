package datadog.trace.core.propagation

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.util.test.DDSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.HaystackHttpCodec.OT_BAGGAGE_PREFIX
import static datadog.trace.core.propagation.HaystackHttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.HaystackHttpCodec.TRACE_ID_KEY

class HaystackHttpExtractorTest extends DDSpecification {

  HttpCodec.Extractor extractor = new HaystackHttpCodec.Extractor(["SOME_HEADER": "some-tag"])

  def "extract http headers"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : traceUuid,
      (SPAN_ID_KEY.toUpperCase())             : spanUuid,
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, MapGetter.INSTANCE)

    then:
    context.traceId == new BigInteger(traceId)
    context.spanId == new BigInteger(spanId)
    context.baggage == ["k1": "v1", "k2": "v2", "Haystack-Trace-ID": traceUuid, "Haystack-Span-ID": spanUuid]
    context.tags == ["some-tag": "my-interesting-info"]
    context.samplingPriority == samplingPriority
    context.origin == origin

    where:
    traceId               | spanId                | samplingPriority              | origin | traceUuid                              | spanUuid
    "1"                   | "2"                   | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-0000-000000000001" | "44617461-646f-6721-0000-000000000002"
    "2"                   | "3"                   | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-0000-000000000002" | "44617461-646f-6721-0000-000000000003"
    "${TRACE_ID_MAX}"     | "${TRACE_ID_MAX - 6}" | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-ffff-ffffffffffff" | "44617461-646f-6721-ffff-fffffffffff9"
    "${TRACE_ID_MAX - 1}" | "${TRACE_ID_MAX - 7}" | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-ffff-fffffffffffe" | "44617461-646f-6721-ffff-fffffffffff8"
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
    traceId                                | spanId                                | expectedTraceId      | expectedSpanId
    "-1"                                   | "1"                                   | null                 | 0G
    "1"                                    | "-1"                                  | null                 | 0G
    "0"                                    | "1"                                   | null                 | 0G
    "00001"                                | "00001"                               | 1G                   | 1G
    "463ac35c9f6413ad"                     | "463ac35c9f6413ad"                    | 5060571933882717101G | 5060571933882717101G
    "463ac35c9f6413ad48485a3953bb6124"     | "1"                                   | 5208512171318403364G | 1G
    "44617461-646f-6721-463a-c35c9f6413ad" |"44617461-646f-6721-463a-c35c9f6413ad" | 5060571933882717101G | 5060571933882717101G
    "f" * 16                               | "1"                                   | TRACE_ID_MAX         | 1G
    "a" * 16 + "f" * 16                    | "1"                                   | TRACE_ID_MAX         | 1G
    "1" + "f" * 32                         | "1"                                   | null                 | 1G
    "0" + "f" * 32                         | "1"                                   | null                 | 1G
    "1"                                    | "f" * 16                              | 1G                   | TRACE_ID_MAX
    "1"                                    | "1" + "f" * 16                        | null                 | 0G
    "1"                                    | "000" + "f" * 16                      | 1G                   | TRACE_ID_MAX
  }
}
