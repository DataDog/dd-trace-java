package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DDId
import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.PropagationStyle.B3
import static datadog.trace.api.PropagationStyle.DATADOG
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY

class HttpInjectorTest extends DDCoreSpecification {

  def "inject http headers"() {
    setup:
    Config config = Mock(Config) {
      getPropagationStylesToInject() >> styles
    }
    HttpCodec.Injector injector = HttpCodec.createInjector(config)

    def traceId = DDId.ONE
    def spanId = DDId.from(2)

    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
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
      samplingMechanism,
      origin,
      ["k1": "v1", "k2": "v2"],
      false,
      "fakeType",
      0,
      tracer.pendingTraceFactory.create(DDId.ONE),
      null,
      false)

    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    if (styles.contains(DATADOG)) {
      1 * carrier.put(DatadogHttpCodec.TRACE_ID_KEY, traceId.toString())
      1 * carrier.put(DatadogHttpCodec.SPAN_ID_KEY, spanId.toString())
      1 * carrier.put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k1", "v1")
      1 * carrier.put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k2", "v2")
      if (samplingPriority != UNSET) {
        1 * carrier.put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, "$samplingPriority")
      }
      if (origin) {
        1 * carrier.put(DatadogHttpCodec.ORIGIN_KEY, origin)
      }
    }
    if (styles.contains(B3)) {
      1 * carrier.put(B3HttpCodec.TRACE_ID_KEY, traceId.toString())
      1 * carrier.put(B3HttpCodec.SPAN_ID_KEY, spanId.toString())
      if (samplingPriority != UNSET) {
        1 * carrier.put(B3HttpCodec.SAMPLING_PRIORITY_KEY, "1")
        1 * carrier.put(B3_KEY, traceId.toString() + "-" + spanId.toString() + "-1")
      } else {
        1 * carrier.put(B3_KEY, traceId.toString() + "-" + spanId.toString())
      }
    }
    0 * _

    cleanup:
    tracer.close()

    where:
    // spotless:off
    styles        | samplingPriority | samplingMechanism | origin
    [DATADOG, B3] | UNSET            | UNKNOWN           | null
    [DATADOG, B3] | SAMPLER_KEEP     | DEFAULT           | "saipan"
    [DATADOG]     | UNSET            | UNKNOWN           | null
    [DATADOG]     | SAMPLER_KEEP     | DEFAULT           | "saipan"
    [B3]          | UNSET            | UNKNOWN           | null
    [B3]          | SAMPLER_KEEP     | DEFAULT           | "saipan"
    [B3, DATADOG] | SAMPLER_KEEP     | DEFAULT           | "saipan"
    // spotless:on
  }

  def "inject http headers using #style"() {
    setup:
    def traceId = DDId.ONE
    def spanId = DDId.from(2)

    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
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
      samplingMechanism,
      origin,
      ["k1": "v1", "k2": "v2"],
      false,
      "fakeType",
      0,
      tracer.pendingTraceFactory.create(DDId.ONE),
      null,
      false)

    final Map<String, String> carrier = Mock()

    when:
    HttpCodec.inject(mockedContext, carrier, MapSetter.INSTANCE, style)

    then:
    if (style == DATADOG) {
      1 * carrier.put(DatadogHttpCodec.TRACE_ID_KEY, traceId.toString())
      1 * carrier.put(DatadogHttpCodec.SPAN_ID_KEY, spanId.toString())
      1 * carrier.put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k1", "v1")
      1 * carrier.put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k2", "v2")
      if (samplingPriority != UNSET) {
        1 * carrier.put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, "$samplingPriority")
      }
      if (origin) {
        1 * carrier.put(DatadogHttpCodec.ORIGIN_KEY, origin)
      }
    } else if (style == B3) {
      1 * carrier.put(B3HttpCodec.TRACE_ID_KEY, traceId.toString())
      1 * carrier.put(B3HttpCodec.SPAN_ID_KEY, spanId.toString())
      if (samplingPriority != UNSET) {
        1 * carrier.put(B3HttpCodec.SAMPLING_PRIORITY_KEY, "1")
        1 * carrier.put(B3_KEY, traceId.toString() + "-" + spanId.toString() + "-1")
      } else {
        1 * carrier.put(B3_KEY, traceId.toString() + "-" + spanId.toString())
      }
    }
    0 * _

    cleanup:
    tracer.close()

    where:
    // spotless:off
    style   | samplingPriority | samplingMechanism | origin
    DATADOG | UNSET            | UNKNOWN           | null
    DATADOG | SAMPLER_KEEP     | DEFAULT           | null
    DATADOG | SAMPLER_KEEP     | DEFAULT           | "saipan"
    B3      | UNSET            | UNKNOWN           | null
    B3      | SAMPLER_KEEP     | DEFAULT           | null
    B3      | SAMPLER_KEEP     | DEFAULT           | "saipan"
    // spotless:on
  }
}
