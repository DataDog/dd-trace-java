package datadog.trace.core.propagation

import datadog.trace.api.DDId
import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.DatadogHttpCodec.*

class DatadogHttpInjectorTest extends DDCoreSpecification {

  HttpCodec.Injector injector = DatadogHttpCodec.INJECTOR

  def "inject http headers"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
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
      samplingMechanism,
      origin,
      ["k1" : "v1", "k2" : "v2"],
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
    1 * carrier.put(TRACE_ID_KEY, traceId.toString())
    1 * carrier.put(SPAN_ID_KEY, spanId.toString())
    if (samplingPriority != UNSET) {
      1 * carrier.put(SAMPLING_PRIORITY_KEY, "$samplingPriority")
    }
    if (origin) {
      1 * carrier.put(ORIGIN_KEY, origin)
    }
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k1", "v1")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k2", "v2")
    0 * _

    cleanup:
    tracer.close()

    where:
    traceId               | spanId                | samplingPriority | samplingMechanism | origin
    "1"                   | "2"                   | UNSET            | UNKNOWN           | null
    "1"                   | "2"                   | SAMPLER_KEEP     | DEFAULT           | "saipan"
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | UNSET            | UNKNOWN           | "saipan"
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | SAMPLER_KEEP     | DEFAULT           | null
  }

  def "inject http headers with end-to-end"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDId.from("1"),
      DDId.from("2"),
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      UNKNOWN,
      "fakeOrigin",
      ["k1" : "v1", "k2" : "v2"],
      false,
      "fakeType",
      0,
      tracer.pendingTraceFactory.create(DDId.ONE),
      null,
      false)

    mockedContext.beginEndToEnd()

    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(TRACE_ID_KEY, "1")
    1 * carrier.put(SPAN_ID_KEY, "2")
    1 * carrier.put(ORIGIN_KEY, "fakeOrigin")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "t0", "${(long) (mockedContext.endToEndStartTime / 1000000L)}")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k1", "v1")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k2", "v2")
    0 * _

    cleanup:
    tracer.close()
  }
}
