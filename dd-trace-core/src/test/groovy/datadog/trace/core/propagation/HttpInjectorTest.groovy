package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.PropagationStyle.B3
import static datadog.trace.api.PropagationStyle.DATADOG

class HttpInjectorTest extends DDSpecification {

  def "inject http headers"() {
    setup:
    Config config = Mock(Config) {
      getPropagationStylesToInject() >> styles
    }
    HttpCodec.Injector injector = HttpCodec.createInjector(config)

    def traceId = DDId.ONE
    def spanId = DDId.from(2)

    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    final DDSpanContext mockedContext =
      new DDSpanContext(
        traceId,
        spanId,
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
    if (styles.contains(DATADOG)) {
      1 * carrier.put(DatadogHttpCodec.TRACE_ID_KEY, traceId.toString())
      1 * carrier.put(DatadogHttpCodec.SPAN_ID_KEY, spanId.toString())
      1 * carrier.put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k1", "v1")
      1 * carrier.put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k2", "v2")
      if (samplingPriority != PrioritySampling.UNSET) {
        1 * carrier.put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, "$samplingPriority")
      }
      if (origin) {
        1 * carrier.put(DatadogHttpCodec.ORIGIN_KEY, origin)
      }
    }
    if (styles.contains(B3)) {
      1 * carrier.put(B3HttpCodec.TRACE_ID_KEY, traceId.toString())
      1 * carrier.put(B3HttpCodec.SPAN_ID_KEY, spanId.toString())
      if (samplingPriority != PrioritySampling.UNSET) {
        1 * carrier.put(B3HttpCodec.SAMPLING_PRIORITY_KEY, "1")
      }
    }
    0 * _

    cleanup:
    tracer.close()
    
    where:
    styles        | samplingPriority              | origin
    [DATADOG, B3] | PrioritySampling.UNSET        | null
    [DATADOG, B3] | PrioritySampling.SAMPLER_KEEP | "saipan"
    [DATADOG]     | PrioritySampling.UNSET        | null
    [DATADOG]     | PrioritySampling.SAMPLER_KEEP | "saipan"
    [B3]          | PrioritySampling.UNSET        | null
    [B3]          | PrioritySampling.SAMPLER_KEEP | "saipan"
    [B3, DATADOG] | PrioritySampling.SAMPLER_KEEP | "saipan"
  }
}
