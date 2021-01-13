package datadog.trace.core.propagation

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_PARENT_ID_BAGGAGE_KEY
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_SPAN_ID_BAGGAGE_KEY
import static datadog.trace.core.propagation.HaystackHttpCodec.DD_TRACE_ID_BAGGAGE_KEY
import static datadog.trace.core.propagation.HaystackHttpCodec.HAYSTACK_TRACE_ID_BAGGAGE_KEY
import static datadog.trace.core.propagation.HaystackHttpCodec.OT_BAGGAGE_PREFIX
import static datadog.trace.core.propagation.HaystackHttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.HaystackHttpCodec.TRACE_ID_KEY

class HaystackHttpInjectorTest extends DDSpecification {

  HttpCodec.Injector injector = new HaystackHttpCodec.Injector()

  def "inject http headers"() {
    setup:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    final DDSpanContext mockedContext =
      new DDSpanContext(
        DDId.from(traceId),
        DDId.from(spanId),
        DDId.ZERO,
        null,
        "fakeService",
        "fakeOperation",
        "fakeResource",
        samplingPriority,
        origin,
        new HashMap<String, String>() {
          {
            put("k1", "v1")
            put("k2", "v2")
          }
        },
        false,
        "fakeType",
        0,
        tracer.pendingTraceFactory.create(DDId.ONE))

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
    1 * carrier.put(DD_PARENT_ID_BAGGAGE_KEY, "0")

    cleanup:
    tracer.close()

    where:
    traceId               | spanId                | samplingPriority              | origin | traceUuid                              | spanUuid
    "1"                   | "2"                   | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-0000-000000000001" | "44617461-646f-6721-0000-000000000002"
    "1"                   | "2"                   | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-0000-000000000001" | "44617461-646f-6721-0000-000000000002"
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-ffff-ffffffffffff" | "44617461-646f-6721-ffff-fffffffffffe"
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-ffff-fffffffffffe" | "44617461-646f-6721-ffff-ffffffffffff"
  }

  def "inject http headers with haystack traceId in baggage"() {
    setup:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    def haystackUuid = traceUuid
    final DDSpanContext mockedContext =
      new DDSpanContext(
        DDId.from(traceId),
        DDId.from(spanId),
        DDId.ZERO,
        null,
        "fakeService",
        "fakeOperation",
        "fakeResource",
        samplingPriority,
        origin,
        new HashMap<String, String>() {
          {
            put("k1", "v1")
            put("k2", "v2")
            put(HAYSTACK_TRACE_ID_BAGGAGE_KEY, haystackUuid)
          }
        },
        false,
        "fakeType",
        0,
        tracer.pendingTraceFactory.create(DDId.ONE))

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
    traceId               | spanId                | samplingPriority              | origin | traceUuid                              | spanUuid
    "1"                   | "2"                   | PrioritySampling.SAMPLER_KEEP | null   | "54617461-646f-6721-0000-000000000001" | "44617461-646f-6721-0000-000000000002"
    "1"                   | "2"                   | PrioritySampling.SAMPLER_KEEP | null   | "54617461-646f-6721-0000-000000000001" | "44617461-646f-6721-0000-000000000002"
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | PrioritySampling.SAMPLER_KEEP | null   | "54617461-646f-6721-ffff-ffffffffffff" | "44617461-646f-6721-ffff-fffffffffffe"
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | PrioritySampling.SAMPLER_KEEP | null   | "54617461-646f-6721-ffff-fffffffffffe" | "44617461-646f-6721-ffff-ffffffffffff"
  }
}
