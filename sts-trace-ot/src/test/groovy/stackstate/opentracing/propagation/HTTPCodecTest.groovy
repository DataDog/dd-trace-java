package stackstate.opentracing.propagation

import stackstate.opentracing.STSSpanContext
import stackstate.opentracing.STSTracer
import stackstate.opentracing.PendingTrace
import stackstate.trace.api.sampling.PrioritySampling
import stackstate.trace.common.writer.ListWriter
import io.opentracing.propagation.TextMapExtractAdapter
import io.opentracing.propagation.TextMapInjectAdapter
import spock.lang.Shared
import spock.lang.Specification

class HTTPCodecTest extends Specification {
  @Shared
  private static final String OT_BAGGAGE_PREFIX = "ot-baggage-"
  @Shared
  private static final String TRACE_ID_KEY = "x-stackstate-trace-id"
  @Shared
  private static final String SPAN_ID_KEY = "x-stackstate-parent-id"
  @Shared
  private static final String SAMPLING_PRIORITY_KEY = "x-stackstate-sampling-priority"

  HTTPCodec codec = new HTTPCodec(["SOME_HEADER": "some-tag"])

  def "inject http headers"() {
    setup:
    def writer = new ListWriter()
    def tracer = new STSTracer(writer)
    final STSSpanContext mockedContext =
      new STSSpanContext(
        1L,
        2L,
        0L,
        "fakeService",
        "fakeOperation",
        "fakeResource",
        samplingPriority,
        new HashMap<String, String>() {
          {
            put("k1", "v1")
            put("k2", "v2")
          }
        },
        false,
        "fakeType",
        null,
        new PendingTrace(tracer, 1L),
        tracer)

    final Map<String, String> carrier = new HashMap<>()

    codec.inject(mockedContext, new TextMapInjectAdapter(carrier))

    expect:
    carrier.get(TRACE_ID_KEY) == "1"
    carrier.get(SPAN_ID_KEY) == "2"
    carrier.get(SAMPLING_PRIORITY_KEY) == (samplingPriority == PrioritySampling.UNSET ? null : String.valueOf(samplingPriority))
    carrier.get(OT_BAGGAGE_PREFIX + "k1") == "v1"
    carrier.get(OT_BAGGAGE_PREFIX + "k2") == "v2"

    where:
    samplingPriority              | _
    PrioritySampling.UNSET        | _
    PrioritySampling.SAMPLER_KEEP | _
  }

  def "extract http headers"() {
    setup:
    final Map<String, String> actual = [
      (TRACE_ID_KEY.toUpperCase())            : "1",
      (SPAN_ID_KEY.toUpperCase())             : "2",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    if (samplingPriority != PrioritySampling.UNSET) {
      actual.put(SAMPLING_PRIORITY_KEY, String.valueOf(samplingPriority))
    }

    final ExtractedContext context = codec.extract(new TextMapExtractAdapter(actual))

    expect:
    context.getTraceId() == 1l
    context.getSpanId() == 2l
    context.getBaggage().get("k1") == "v1"
    context.getBaggage().get("k2") == "v2"
    context.getTags() == ["some-tag": "my-interesting-info"]
    context.getSamplingPriority() == samplingPriority

    where:
    samplingPriority              | _
    PrioritySampling.UNSET        | _
    PrioritySampling.SAMPLER_KEEP | _
  }
}
