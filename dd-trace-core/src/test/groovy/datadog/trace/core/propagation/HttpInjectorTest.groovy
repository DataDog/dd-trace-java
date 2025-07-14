package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.StringUtils

import static datadog.trace.api.TracePropagationStyle.HAYSTACK
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT
import static datadog.trace.api.sampling.PrioritySampling.*
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.TracePropagationStyle.B3SINGLE
import static datadog.trace.api.TracePropagationStyle.B3MULTI
import static datadog.trace.api.TracePropagationStyle.DATADOG
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY

class HttpInjectorTest extends DDCoreSpecification {

  boolean tracePropagationB3Padding() {
    return false
  }

  String idOrPadded(DDTraceId id) {
    if (id.toHighOrderLong() == 0) {
      return idOrPadded(DDSpanId.toHexString(id.toLong()), 32)
    }
    return id.toHexString()
  }

  String idOrPadded(long id) {
    return idOrPadded(DDSpanId.toHexString(id), 16)
  }

  String idOrPadded(String id, int size) {
    if (!tracePropagationB3Padding()) {
      return id.toLowerCase()
    }
    return StringUtils.padHexLower(id, size)
  }

  def "inject http headers using #styles"() {
    setup:
    Config config = Mock(Config) {
      getTracePropagationStylesToInject() >> styles
      isTracePropagationStyleB3PaddingEnabled() >> tracePropagationB3Padding()
    }
    HttpCodec.Injector injector = HttpCodec.createInjector(config, config.getTracePropagationStylesToInject(), [:])
    def traceId = DDTraceId.ONE
    def spanId = 2
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext = mockedContext(tracer, traceId, spanId, samplingPriority, origin, ["k1": "v1", "k2": "v2"])
    final Map<String, String> carrier = Mock()
    def b3TraceIdHex = idOrPadded(traceId)
    def b3SpanIdHex = idOrPadded(spanId)

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
      1 * carrier.put('x-datadog-tags', '_dd.p.usr=123')
    }
    if (styles.contains(B3MULTI)) {
      1 * carrier.put(B3HttpCodec.TRACE_ID_KEY, b3TraceIdHex)
      1 * carrier.put(B3HttpCodec.SPAN_ID_KEY, b3SpanIdHex)
      if (samplingPriority != UNSET) {
        1 * carrier.put(B3HttpCodec.SAMPLING_PRIORITY_KEY, "1")
      }
    }
    if (styles.contains(B3SINGLE)) {
      if (samplingPriority != UNSET) {
        1 * carrier.put(B3_KEY, "$b3TraceIdHex-$b3SpanIdHex-1")
      } else {
        1 * carrier.put(B3_KEY, "$b3TraceIdHex-$b3SpanIdHex")
      }
    }
    0 * _

    cleanup:
    tracer.close()

    where:
    // spotless:off
    styles                       | samplingPriority | origin
    [DATADOG, B3SINGLE]          | UNSET            | null
    [DATADOG, B3SINGLE]          | SAMPLER_KEEP     | "saipan"
    [DATADOG]                    | UNSET            | null
    [DATADOG]                    | SAMPLER_KEEP     | "saipan"
    [B3SINGLE]                   | UNSET            | null
    [B3SINGLE]                   | SAMPLER_KEEP     | "saipan"
    [B3SINGLE, DATADOG]          | SAMPLER_KEEP     | "saipan"
    [DATADOG, B3MULTI, B3SINGLE] | UNSET            | null
    [DATADOG, B3MULTI, B3SINGLE] | SAMPLER_KEEP     | "saipan"
    [DATADOG, B3MULTI]           | UNSET            | null
    [DATADOG, B3MULTI]           | SAMPLER_KEEP     | "saipan"
    [B3MULTI]                    | UNSET            | null
    [B3MULTI]                    | SAMPLER_KEEP     | "saipan"
    [B3MULTI, DATADOG]           | SAMPLER_KEEP     | "saipan"
    // spotless:on
  }

  def "inject http headers using #style"() {
    setup:
    Config config = Mock(Config) {
      isTracePropagationStyleB3PaddingEnabled() >> tracePropagationB3Padding()
    }
    def injector = HttpCodec.createInjector(config, [style].toSet(), ["some-baggage-item": "SOME_HEADER"],)
    def traceId = DDTraceId.ONE
    def spanId = 2
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext = mockedContext(tracer, traceId, spanId, samplingPriority, origin, ["k1": "v1", "k2": "v2","some-baggage-item":"some-baggage-value"])
    final Map<String, String> carrier = Mock()
    def b3TraceIdHex = idOrPadded(traceId)
    def b3SpanIdHex = idOrPadded(spanId)

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    if (style == DATADOG) {
      1 * carrier.put(DatadogHttpCodec.TRACE_ID_KEY, traceId.toString())
      1 * carrier.put(DatadogHttpCodec.SPAN_ID_KEY, spanId.toString())
      1 * carrier.put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k1", "v1")
      1 * carrier.put(DatadogHttpCodec.OT_BAGGAGE_PREFIX + "k2", "v2")
      1 * carrier.put("SOME_HEADER", "some-baggage-value")
      if (samplingPriority != UNSET) {
        1 * carrier.put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, "$samplingPriority")
      }
      if (origin) {
        1 * carrier.put(DatadogHttpCodec.ORIGIN_KEY, origin)
      }
      1 * carrier.put('x-datadog-tags', '_dd.p.usr=123')
    } else if (style == B3MULTI) {
      1 * carrier.put(B3HttpCodec.TRACE_ID_KEY, b3TraceIdHex)
      1 * carrier.put(B3HttpCodec.SPAN_ID_KEY, b3SpanIdHex)
      if (samplingPriority != UNSET) {
        1 * carrier.put(B3HttpCodec.SAMPLING_PRIORITY_KEY, "1")
      }
    } else if (style == B3SINGLE) {
      if (samplingPriority != UNSET) {
        1 * carrier.put(B3_KEY, "$b3TraceIdHex-$b3SpanIdHex-1")
      } else {
        1 * carrier.put(B3_KEY, "$b3TraceIdHex-$b3SpanIdHex")
      }
    }
    0 * _

    cleanup:
    tracer.close()

    where:
    // spotless:off
    style    | samplingPriority | origin
    DATADOG  | UNSET            | null
    DATADOG  | SAMPLER_KEEP     | null
    DATADOG  | SAMPLER_KEEP     | "saipan"
    B3SINGLE | UNSET            | null
    B3SINGLE | SAMPLER_KEEP     | null
    B3SINGLE | SAMPLER_KEEP     | "saipan"
    B3MULTI  | UNSET            | null
    B3MULTI  | SAMPLER_KEEP     | null
    B3MULTI  | SAMPLER_KEEP     | "saipan"
    // spotless:on
  }

  def "encode baggage in http headers using #style"() {
    setup:
    Config config = Mock(Config) {
      isTracePropagationStyleB3PaddingEnabled() >> tracePropagationB3Padding()
    }
    def baggage = [
      'alpha': 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ',
      'num': '01234567890',
      'whitespace': 'ab \tcd',
      'specials': 'ab.-*_cd',
      'excluded': 'ab\',:\\cd',
    ]
    def mapping = baggage.keySet().collectEntries { [(it):it]} as Map<String, String>
    def injector = HttpCodec.createInjector(config, [style].toSet(), mapping)
    def traceId = DDTraceId.ONE
    def spanId = 2
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext = mockedContext(tracer, traceId, spanId, UNSET, null, baggage)
    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put('alpha', 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ')
    1 * carrier.put('num', '01234567890')
    1 * carrier.put('whitespace', 'ab%20%09cd')
    1 * carrier.put('specials', 'ab.-*_cd')
    1 * carrier.put('excluded', 'ab%27%2C%3A%5Ccd')

    cleanup:
    tracer.close()

    where:
    style << [DATADOG, TRACECONTEXT, HAYSTACK]
  }

  def "inject ot-baggage-* in http headers only once"() {
    setup:
    Config config = Mock(Config)
    def baggage = [
      'k1': 'v1',
      'k2': 'v2',
    ]
    def mapping = baggage.keySet().collectEntries { [(it):it]} as Map<String, String>
    def injector = HttpCodec.createInjector(config, [DATADOG, TRACECONTEXT].toSet(), mapping)
    def traceId = DDTraceId.ONE
    def spanId = 2
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext = mockedContext(tracer, traceId, spanId, UNSET, null, baggage)
    final Map<String, String> carrier = Mock()
    mockedContext.beginEndToEnd()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * carrier.put('k1', 'v1')
    1 * carrier.put('k2', 'v2')
    1 * carrier.put('ot-baggage-t0', "${(long) (mockedContext.endToEndStartTime / 1000000L)}")

    cleanup:
    tracer.close()
  }

  static DDSpanContext mockedContext(CoreTracer tracer, DDTraceId traceId, long spanId, int samplingPriority, String origin, Map<String, String> baggage) {
    return new DDSpanContext(
      traceId,
      spanId,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      samplingPriority,
      origin,
      baggage,
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"))
  }
}

class HttpInjectorB3PaddingTest extends HttpInjectorTest {
  @Override
  boolean tracePropagationB3Padding() {
    return true
  }
}
