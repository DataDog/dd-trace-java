package datadog.trace.core.propagation

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId

import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.HaystackHttpCodec.*

class HaystackHttpInjectorTest extends DDCoreSpecification {

  HttpCodec.Injector injector = HaystackHttpCodec.newInjector(["some-baggage-key":"SOME_CUSTOM_HEADER"])

  def "inject http headers"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDTraceId.from(traceId),
      DDSpanId.from(spanId),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      samplingPriority,
      origin,
      ["k1" : "v1", "k2" : "v2", "some-baggage-key": "some-value"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      null)

    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(TRACE_ID_KEY, traceUuid)
    mockedContext.getTags().get(HAYSTACK_TRACE_ID_BAGGAGE_KEY) == traceUuid
    1 * carrier.put(DD_TRACE_ID_BAGGAGE_KEY, traceId.toString())
    1 * carrier.put(SPAN_ID_KEY, spanUuid)
    1 * carrier.put(DD_SPAN_ID_BAGGAGE_KEY, spanId.toString())
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k1", "v1")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k2", "v2")
    1 * carrier.put("SOME_CUSTOM_HEADER", "some-value")
    1 * carrier.put(DD_PARENT_ID_BAGGAGE_KEY, "0")

    cleanup:
    tracer.close()

    where:
    traceId               | spanId                | samplingPriority | samplingMechanism | origin | traceUuid                              | spanUuid
    "1"                   | "2"                   | SAMPLER_KEEP     | DEFAULT           | null   | "44617461-646f-6721-0000-000000000001" | "44617461-646f-6721-0000-000000000002"
    "1"                   | "2"                   | SAMPLER_KEEP     | DEFAULT           | null   | "44617461-646f-6721-0000-000000000001" | "44617461-646f-6721-0000-000000000002"
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | SAMPLER_KEEP     | DEFAULT           | null   | "44617461-646f-6721-ffff-ffffffffffff" | "44617461-646f-6721-ffff-fffffffffffe"
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | SAMPLER_KEEP     | DEFAULT           | null   | "44617461-646f-6721-ffff-fffffffffffe" | "44617461-646f-6721-ffff-ffffffffffff"
  }

  def "inject http headers with haystack traceId in baggage"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def haystackUuid = traceUuid
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDTraceId.from(traceId),
      DDSpanId.from(spanId),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      samplingPriority,
      origin,
      ["k1" : "v1", "k2" : "v2", (HAYSTACK_TRACE_ID_BAGGAGE_KEY) : haystackUuid],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      null)

    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(TRACE_ID_KEY, traceUuid)
    1 * carrier.put(DD_TRACE_ID_BAGGAGE_KEY, traceId.toString())
    1 * carrier.put(SPAN_ID_KEY, spanUuid)
    1 * carrier.put(DD_SPAN_ID_BAGGAGE_KEY, spanId.toString())
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k1", "v1")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k2", "v2")

    cleanup:
    tracer.close()

    where:
    traceId               | spanId                | samplingPriority | samplingMechanism | origin | traceUuid                              | spanUuid
    "1"                   | "2"                   | SAMPLER_KEEP     | DEFAULT           | null   | "54617461-646f-6721-0000-000000000001" | "44617461-646f-6721-0000-000000000002"
    "1"                   | "2"                   | SAMPLER_KEEP     | DEFAULT           | null   | "54617461-646f-6721-0000-000000000001" | "44617461-646f-6721-0000-000000000002"
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | SAMPLER_KEEP     | DEFAULT           | null   | "54617461-646f-6721-ffff-ffffffffffff" | "44617461-646f-6721-ffff-fffffffffffe"
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | SAMPLER_KEEP     | DEFAULT           | null   | "54617461-646f-6721-ffff-fffffffffffe" | "44617461-646f-6721-ffff-ffffffffffff"
  }
}
