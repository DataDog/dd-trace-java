package datadog.trace.core.propagation

import datadog.trace.api.DDId
import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY

class B3HttpInjectorTest extends DDCoreSpecification {

  HttpCodec.Injector injector = B3HttpCodec.INJECTOR

  def "inject http headers"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDId.from("$traceId"),
      DDId.from("$spanId"),
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      samplingPriority,
      samplingMechanism,
      "fakeOrigin",
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
    1 * carrier.put(TRACE_ID_KEY, traceId.toString(16).toLowerCase())
    1 * carrier.put(SPAN_ID_KEY, spanId.toString(16).toLowerCase())
    if (expectedSamplingPriority != null) {
      1 * carrier.put(SAMPLING_PRIORITY_KEY, "$expectedSamplingPriority")
      1 * carrier.put(B3_KEY, traceId.toString(16).toLowerCase() + "-" + spanId.toString(16).toLowerCase() + "-$expectedSamplingPriority")
    } else {
      1 * carrier.put(B3_KEY, traceId.toString(16).toLowerCase() + "-" + spanId.toString(16).toLowerCase())
    }
    0 * _

    cleanup:
    tracer.close()

    where:
    traceId          | spanId           | samplingPriority | samplingMechanism | expectedSamplingPriority
    1G               | 2G               | UNSET            | UNKNOWN           | null
    2G               | 3G               | SAMPLER_KEEP     | DEFAULT           | SAMPLER_KEEP
    4G               | 5G               | SAMPLER_DROP     | DEFAULT           | SAMPLER_DROP
    5G               | 6G               | USER_KEEP        | MANUAL            | SAMPLER_KEEP
    6G               | 7G               | USER_DROP        | MANUAL            | SAMPLER_DROP
    TRACE_ID_MAX     | TRACE_ID_MAX - 1 | UNSET            | UNKNOWN           | null
    TRACE_ID_MAX - 1 | TRACE_ID_MAX     | SAMPLER_KEEP     | DEFAULT           | SAMPLER_KEEP
  }

  def "inject http headers with extracted original"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): traceId,
      (SPAN_ID_KEY.toUpperCase()) : spanId,
    ]
    HttpCodec.Extractor extractor = B3HttpCodec.newExtractor(Collections.emptyMap())
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())
    final DDSpanContext mockedContext =
      new DDSpanContext(
      context.traceId,
      context.spanId,
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      UNKNOWN,
      "fakeOrigin",
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
    1 * carrier.put(TRACE_ID_KEY, traceId)
    1 * carrier.put(SPAN_ID_KEY, spanId)
    1 * carrier.put(B3_KEY, traceId + "-" + spanId)
    0 * _

    cleanup:
    tracer.close()

    where:
    traceId                            | spanId
    "00001"                            | "00001"
    "463ac35c9f6413ad"                 | "463ac35c9f6413ad"
    "463ac35c9f6413ad48485a3953bb6124" | "1"
    "f" * 16                           | "1"
    "a" * 16 + "f" * 16                | "1"
    "1"                                | "f" * 16
    "1"                                | "000" + "f" * 16
  }
}
