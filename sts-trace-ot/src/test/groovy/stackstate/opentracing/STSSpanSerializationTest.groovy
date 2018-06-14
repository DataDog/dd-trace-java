package stackstate.opentracing

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Maps
import stackstate.trace.api.STSTags
import stackstate.trace.common.sampling.PrioritySampling
import stackstate.trace.common.writer.ListWriter
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

@Timeout(5)
class STSSpanSerializationTest extends Specification {

  @Unroll
  def "serialize spans"() throws Exception {
    setup:
    def fakePidProvider = [getPid: {-> return (Long)42}] as ISTSSpanContextPidProvider
    def fakeHostNameProvider = [getHostName: {-> return "fakehost"}] as ISTSSpanContextHostNameProvider
    final Map<String, String> baggage = new HashMap<>()
    baggage.put("a-baggage", "value")
    final Map<String, Object> tags = new HashMap<>()
    baggage.put("k1", "v1")

    Map<String, Object> expected = Maps.newHashMap()
    expected.put("meta", baggage)
    expected.put("service", "service")
    expected.put("error", 0)
    expected.put("type", "type")
    expected.put("name", "operation")
    expected.put("duration", 33000)
    expected.put("resource", "operation")
    if (samplingPriority != PrioritySampling.UNSET) {
      expected.put("sampling_priority", samplingPriority)
    }
    expected.put("start", 100000)
    expected.put("span_id", 2l)
    expected.put("parent_id", 0l)
    expected.put("trace_id", 1l)

    def writer = new ListWriter()
    def tracer = new STSTracer(writer)
    final STSSpanContext context =
      new STSSpanContext(
        1L,
        2L,
        0L,
        "service",
        "operation",
        null,
        samplingPriority,
        new HashMap<>(baggage),
        false,
        "type",
        tags,
        new PendingTrace(tracer, 1L),
        tracer)
    context.setHostNameProvider(fakeHostNameProvider)
    context.setPidProvider(fakePidProvider)
    baggage.put(STSTags.SPAN_HOSTNAME, "fakehost")
    baggage.put(STSTags.SPAN_PID, "42")
    baggage.put(STSTags.THREAD_NAME, Thread.currentThread().getName())
    baggage.put(STSTags.THREAD_ID, String.valueOf(Thread.currentThread().getId()))
    baggage.put(STSTags.SPAN_TYPE, context.getSpanType())

    STSSpan span = new STSSpan(100L, context)
    span.finish(133L)
    ObjectMapper serializer = new ObjectMapper()

    expect:
    serializer.readTree(serializer.writeValueAsString(span)) == serializer.readTree(serializer.writeValueAsString(expected))

    where:
    samplingPriority               | _
    PrioritySampling.SAMPLER_KEEP  | _
    PrioritySampling.UNSET         | _
  }
}
