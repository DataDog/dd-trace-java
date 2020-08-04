package datadog.trace.core.propagation

import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.api.DDId
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.util.test.DDSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
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
        new PendingTrace(tracer, DDId.ONE),
        tracer,
        [:])

    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(TRACE_ID_KEY, traceId.toString())
    1 * carrier.put(SPAN_ID_KEY, spanId.toString())
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k1", "v1")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k2", "v2")


    where:
    traceId               | spanId                | samplingPriority              | origin
    "1"                   | "2"                   | PrioritySampling.SAMPLER_KEEP | null
    "1"                   | "2"                   | PrioritySampling.SAMPLER_KEEP | null
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | PrioritySampling.SAMPLER_KEEP | null
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | PrioritySampling.SAMPLER_KEEP | null
  }
}
