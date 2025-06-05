package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.DynamicConfig
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.StringUtils

import static datadog.trace.api.sampling.PrioritySampling.*
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
import static datadog.trace.test.util.StringUtils.trimHex

class B3HttpInjectorTest extends DDCoreSpecification {

  boolean tracePropagationB3Padding() {
    return false
  }

  String idOrPadded(BigInteger id, int size) {
    return idOrPadded(id.toString(16), size)
  }

  String idOrPadded(String id, int size) {
    if (!tracePropagationB3Padding()) {
      return id.toLowerCase()
    }
    return StringUtils.padHexLower(id, size)
  }

  def "inject http headers"() {
    setup:
    HttpCodec.Injector injector = B3HttpCodec.newCombinedInjector(tracePropagationB3Padding())
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext = mockedContext(tracer, DDTraceId.from("$traceId"), DDSpanId.from("$spanId"), samplingPriority)
    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(TRACE_ID_KEY, traceIdHex)
    1 * carrier.put(SPAN_ID_KEY, spanIdHex)
    if (expectedSamplingPriority != null) {
      1 * carrier.put(SAMPLING_PRIORITY_KEY, "$expectedSamplingPriority")
      1 * carrier.put(B3_KEY,  "$traceIdHex-$spanIdHex-$expectedSamplingPriority")
    } else {
      1 * carrier.put(B3_KEY,  "$traceIdHex-$spanIdHex")
    }
    0 * _

    cleanup:
    tracer.close()

    where:
    traceId          | spanId           | samplingPriority | expectedSamplingPriority
    1G               | 2G               | UNSET            | null
    2G               | 3G               | SAMPLER_KEEP     | SAMPLER_KEEP
    4G               | 5G               | SAMPLER_DROP     | SAMPLER_DROP
    5G               | 6G               | USER_KEEP        | SAMPLER_KEEP
    6G               | 7G               | USER_DROP        | SAMPLER_DROP
    TRACE_ID_MAX     | TRACE_ID_MAX - 1 | UNSET            | null
    TRACE_ID_MAX - 1 | TRACE_ID_MAX     | SAMPLER_KEEP     | SAMPLER_KEEP

    traceIdHex = idOrPadded(traceId, 32)
    spanIdHex = idOrPadded(spanId, 16)
  }

  def "inject http headers with extracted original"() {
    setup:
    HttpCodec.Injector injector = B3HttpCodec.newCombinedInjector(tracePropagationB3Padding())
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): traceId,
      (SPAN_ID_KEY.toUpperCase()) : spanId,
    ]
    DynamicConfig dynamicConfig = DynamicConfig.create()
      .setHeaderTags([:])
      .setBaggageMapping([:])
      .apply()
    HttpCodec.Extractor extractor = B3HttpCodec.newExtractor(Config.get(), { dynamicConfig.captureTraceConfig() })
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())
    final DDSpanContext mockedContext = mockedContext(tracer, context)
    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put(TRACE_ID_KEY, traceIdHex)
    1 * carrier.put(SPAN_ID_KEY, spanIdHex)
    1 * carrier.put(B3_KEY, "$traceIdHex-$spanIdHex")
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

    traceIdHex = idOrPadded(traceId, 32)
    spanIdHex = idOrPadded(trimHex(spanId), 16)
  }

  static DDSpanContext mockedContext(CoreTracer tracer, TagContext context) {
    return mockedContext(tracer, context.traceId, context.spanId, UNSET)
  }

  static DDSpanContext mockedContext(CoreTracer tracer, DDTraceId traceId, long spanId, int samplingPriority) {
    return new DDSpanContext(
      traceId,
      spanId,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      samplingPriority,
      "fakeOrigin",
      ["k1": "v1", "k2": "v2"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty())
  }
}

class B3HttpInjectorPaddedTest extends B3HttpInjectorTest {
  @Override
  boolean tracePropagationB3Padding() {
    return true
  }
}
