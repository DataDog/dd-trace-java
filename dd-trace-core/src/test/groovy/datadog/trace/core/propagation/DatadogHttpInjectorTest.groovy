package datadog.trace.core.propagation

import datadog.trace.api.DD128bTraceId
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.internal.util.LongStringUtils
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.DatadogHttpCodec.*


class DatadogHttpInjectorTest extends DDCoreSpecification {

  HttpCodec.Injector injector = newInjector(["some-baggage-key":"SOME_CUSTOM_HEADER"])

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
      ["k1" : "v1", "k2" : "v2","some-baggage-key": "some-value"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"))

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
    1 * carrier.put("SOME_CUSTOM_HEADER", "some-value")
    1 * carrier.put(DATADOG_TAGS_KEY, "_dd.p.usr=123")
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
      DDTraceId.from("1"),
      DDSpanId.from("2"),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      "fakeOrigin",
      ["k1" : "v1", "k2" : "v2"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-4,_dd.p.anytag=value"))

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
    1 * carrier.put(DATADOG_TAGS_KEY, '_dd.p.dm=-4,_dd.p.anytag=value')
    0 * _

    cleanup:
    tracer.close()
  }

  def "inject the decision maker tag"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDTraceId.from("1"),
      DDSpanId.from("2"),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      "fakeOrigin",
      ["k1" : "v1", "k2" : "v2"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty())

    mockedContext.setSamplingPriority(USER_KEEP, MANUAL)

    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(TRACE_ID_KEY, "1")
    1 * carrier.put(SPAN_ID_KEY, "2")
    1 * carrier.put(ORIGIN_KEY, "fakeOrigin")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k1", "v1")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k2", "v2")
    1 * carrier.put('x-datadog-sampling-priority', '2')
    1 * carrier.put(DATADOG_TAGS_KEY, '_dd.p.dm=-4')
    0 * _

    cleanup:
    tracer.close()
  }

  def "inject http headers with 128-bit TraceId"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def traceId = DD128bTraceId.fromHex(hexId)
    final DDSpanContext mockedContext =
      new DDSpanContext(
      traceId,
      DDSpanId.from("2"),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      null,
      ["k1" : "v1", "k2" : "v2"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-4,_dd.p.anytag=value"))

    mockedContext.beginEndToEnd()

    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(TRACE_ID_KEY, traceId.toString())
    1 * carrier.put(SPAN_ID_KEY, "2")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "t0", "${(long) (mockedContext.endToEndStartTime / 1000000L)}")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k1", "v1")
    1 * carrier.put(OT_BAGGAGE_PREFIX + "k2", "v2")
    if (traceId.toHighOrderLong() == 0) {
      1 * carrier.put(DATADOG_TAGS_KEY, '_dd.p.dm=-4,_dd.p.anytag=value')
    } else {
      def tId = LongStringUtils.toHexStringPadded(traceId.toHighOrderLong(), 16)
      1 * carrier.put(DATADOG_TAGS_KEY, "_dd.p.dm=-4,_dd.p.tid=${tId},_dd.p.anytag=value")
    }
    0 * _

    cleanup:
    tracer.close()

    where:
    hexId << [
      "1",
      "123456789abcdef0",
      "123456789abcdef0123456789abcdef0",
      "64184f2400000000123456789abcdef0",
      "f" * 32
    ]
  }
}
