package datadog.trace.core.propagation

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY

class B3HttpInjectorTest extends DDSpecification {

  HttpCodec.Injector injector = new B3HttpCodec.Injector()

  def "inject http headers"() {
    setup:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
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
        "fakeOrigin",
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
    1 * carrier.put(TRACE_ID_KEY, traceId.toString(16).toLowerCase())
    1 * carrier.put(SPAN_ID_KEY, spanId.toString(16).toLowerCase())
    if (expectedSamplingPriority != null) {
      1 * carrier.put(SAMPLING_PRIORITY_KEY, "$expectedSamplingPriority")
    }
    0 * _

    cleanup:
    tracer.close()
    
    where:
    traceId          | spanId           | samplingPriority              | expectedSamplingPriority
    1G               | 2G               | PrioritySampling.UNSET        | null
    2G               | 3G               | PrioritySampling.SAMPLER_KEEP | 1
    4G               | 5G               | PrioritySampling.SAMPLER_DROP | 0
    5G               | 6G               | PrioritySampling.USER_KEEP    | 1
    6G               | 7G               | PrioritySampling.USER_DROP    | 0
    TRACE_ID_MAX     | TRACE_ID_MAX - 1 | PrioritySampling.UNSET        | null
    TRACE_ID_MAX - 1 | TRACE_ID_MAX     | PrioritySampling.SAMPLER_KEEP | 1
  }
}
