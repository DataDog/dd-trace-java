package datadog.trace.core.propagation

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY

class DatadogHttpInjectorTest extends DDSpecification {

  HttpCodec.Injector injector = new DatadogHttpCodec.Injector()

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
    1 * carrier.put(TRACE_ID_KEY, traceId.toString())
    1 * carrier.put(SPAN_ID_KEY, spanId.toString())
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k1", "v1")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k2", "v2")
    if (samplingPriority != PrioritySampling.UNSET) {
      1 * carrier.put(SAMPLING_PRIORITY_KEY, "$samplingPriority")
    }
    if (origin) {
      1 * carrier.put(ORIGIN_KEY, origin)
    }
    0 * _

    cleanup:
    tracer.close()

    where:
    traceId               | spanId                | samplingPriority              | origin
    "1"                   | "2"                   | PrioritySampling.UNSET        | null
    "1"                   | "2"                   | PrioritySampling.SAMPLER_KEEP | "saipan"
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | PrioritySampling.UNSET        | "saipan"
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | PrioritySampling.SAMPLER_KEEP | null
  }
}
