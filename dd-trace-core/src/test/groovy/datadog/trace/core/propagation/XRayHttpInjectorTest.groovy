package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.DynamicConfig
import datadog.trace.api.time.TimeSource
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.core.datastreams.DataStreamsMonitoring

import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX

class XRayHttpInjectorTest extends DDCoreSpecification {

  HttpCodec.Injector injector = XRayHttpCodec.newInjector(["some-baggage-key":"SOME_CUSTOM_HEADER"])

  def "inject http headers"() {
    setup:
    def writer = new ListWriter()
    def timeSource = Mock(TimeSource)
    def tracer = tracerBuilder()
      .dataStreamsMonitoring(Mock(DataStreamsMonitoring))
      .writer(writer)
      .timeSource(timeSource)
      .build()
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDTraceId.from("$traceId"),
      DDSpanId.from("$spanId"),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      samplingPriority,
      "fakeOrigin",
      ["k": "v", "some-baggage-key": "some-value"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      null)

    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * timeSource.getCurrentTimeMillis() >> 1_664_906_869_196
    1 * carrier.put('X-Amzn-Trace-Id', "$expectedTraceHeader")
    0 * _

    cleanup:
    tracer.close()

    where:
    traceId          | spanId           | samplingPriority | samplingMechanism | expectedTraceHeader
    1G               | 2G               | UNSET            | UNKNOWN           | 'Root=1-633c7675-000000000000000000000001;Parent=0000000000000002;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    2G               | 3G               | SAMPLER_KEEP     | DEFAULT           | 'Root=1-633c7675-000000000000000000000002;Parent=0000000000000003;Sampled=1;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    4G               | 5G               | SAMPLER_DROP     | DEFAULT           | 'Root=1-633c7675-000000000000000000000004;Parent=0000000000000005;Sampled=0;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    5G               | 6G               | USER_KEEP        | MANUAL            | 'Root=1-633c7675-000000000000000000000005;Parent=0000000000000006;Sampled=1;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    6G               | 7G               | USER_DROP        | MANUAL            | 'Root=1-633c7675-000000000000000000000006;Parent=0000000000000007;Sampled=0;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    TRACE_ID_MAX     | TRACE_ID_MAX - 1 | UNSET            | UNKNOWN           | 'Root=1-633c7675-00000000ffffffffffffffff;Parent=fffffffffffffffe;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    TRACE_ID_MAX - 1 | TRACE_ID_MAX     | SAMPLER_KEEP     | DEFAULT           | 'Root=1-633c7675-00000000fffffffffffffffe;Parent=ffffffffffffffff;Sampled=1;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
  }

  def "inject http headers with extracted original"() {
    setup:
    def writer = new ListWriter()
    def timeSource = Mock(TimeSource)
    def tracer = tracerBuilder()
      .dataStreamsMonitoring(Mock(DataStreamsMonitoring))
      .writer(writer)
      .timeSource(timeSource)
      .build()
    def headers = [
      'X-Amzn-Trace-Id' : "Root=1-00000000-00000000${traceId.padLeft(16, '0')};Parent=${spanId.padLeft(16, '0')}"
    ]
    DynamicConfig dynamicConfig = DynamicConfig.create()
      .setHeaderTags([:])
      .setBaggageMapping([:])
      .apply()
    HttpCodec.Extractor extractor = XRayHttpCodec.newExtractor(Config.get(), { dynamicConfig.captureTraceConfig() })
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())
    final DDSpanContext mockedContext =
      new DDSpanContext(
      context.traceId,
      context.spanId,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      "fakeOrigin",
      ["k": "v", "some-baggage-key": "some-value"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      null)
    final Map<String, String> carrier = Mock()

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * timeSource.getCurrentTimeMillis() >> 1_664_906_869_196
    1 * carrier.put('X-Amzn-Trace-Id', "$expectedTraceHeader")
    0 * _

    println carrier.toString()

    cleanup:
    tracer.close()

    where:
    traceId            | spanId             | expectedTraceHeader
    "00001"            | "00001"            | 'Root=1-633c7675-000000000000000000000001;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    "463ac35c9f6413ad" | "463ac35c9f6413ad" | 'Root=1-633c7675-00000000463ac35c9f6413ad;Parent=463ac35c9f6413ad;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    "48485a3953bb6124" | "1"                | 'Root=1-633c7675-0000000048485a3953bb6124;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    "f" * 16           | "1"                | 'Root=1-633c7675-00000000ffffffffffffffff;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    "a" * 8 + "f" * 8  | "1"                | 'Root=1-633c7675-00000000aaaaaaaaffffffff;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
    "1"                | "f" * 16           | 'Root=1-633c7675-000000000000000000000001;Parent=ffffffffffffffff;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'
  }

  def "inject http headers with end-to-end"() {
    setup:
    def writer = new ListWriter()
    def timeSource = Mock(TimeSource)
    def tracer = tracerBuilder()
      .dataStreamsMonitoring(Mock(DataStreamsMonitoring))
      .writer(writer)
      .timeSource(timeSource)
      .build()
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
      ["k": "v"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      null)
    final Map<String, String> carrier = Mock()

    when:
    mockedContext.beginEndToEnd()
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    1 * timeSource.getCurrentTimeNanos() >> 1_664_906_869_196_787_813
    1 * timeSource.getNanoTicks() >> 1_664_906_869_196
    1 * carrier.put('X-Amzn-Trace-Id', "Root=1-633c7675-000000000000000000000001;Parent=0000000000000002;_dd.origin=fakeOrigin;t0=1664906869195;k=v")
    0 * _

    cleanup:
    tracer.close()
  }
}
