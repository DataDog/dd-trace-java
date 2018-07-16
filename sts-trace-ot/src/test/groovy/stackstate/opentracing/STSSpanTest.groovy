package stackstate.opentracing

import stackstate.trace.api.sampling.PrioritySampling
import stackstate.trace.common.sampling.RateByServiceSampler
import stackstate.trace.common.writer.ListWriter
import spock.lang.Specification

import java.util.concurrent.TimeUnit

class STSSpanTest extends Specification {
  def writer = new ListWriter()
  def tracer = new STSTracer(STSTracer.UNASSIGNED_DEFAULT_SERVICE_NAME, writer, new RateByServiceSampler())

  def "getters and setters"() {
    setup:
    final STSSpanContext context =
      new STSSpanContext(
        1L,
        1L,
        0L,
        "fakeService",
        "fakeOperation",
        "fakeResource",
        PrioritySampling.UNSET,
        Collections.<String, String> emptyMap(),
        false,
        "fakeType",
        null,
        new PendingTrace(tracer, 1L),
        tracer)

    final STSSpan span = new STSSpan(1L, context)

    when:
    span.setServiceName("service")
    then:
    span.getServiceName() == "service"

    when:
    span.setOperationName("operation")
    then:
    span.getOperationName() == "operation"

    when:
    span.setResourceName("resource")
    then:
    span.getResourceName() == "resource"

    when:
    span.setSpanType("type")
    then:
    span.getType() == "type"

    when:
    span.setSamplingPriority(PrioritySampling.UNSET)
    then:
    span.getSamplingPriority() == null

    when:
    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP)
    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    when:
    context.lockSamplingPriority()
    span.setSamplingPriority(PrioritySampling.USER_KEEP)
    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
  }

  def "resource name equals operation name if null"() {
    setup:
    final String opName = "operationName"
    STSSpan span

    when:
    span = tracer.buildSpan(opName).start()
    then:
    span.getResourceName() == opName
    span.getServiceName() == STSTracer.UNASSIGNED_DEFAULT_SERVICE_NAME

    when:
    final String resourceName = "fake"
    final String serviceName = "myService"
    span = new STSTracer()
      .buildSpan(opName)
      .withResourceName(resourceName)
      .withServiceName(serviceName)
      .start()
    then:
    span.getResourceName() == resourceName
    span.getServiceName() == serviceName
  }

  def "duration measured in nanoseconds"() {
    setup:
    def mod = TimeUnit.MILLISECONDS.toNanos(1)
    def builder = tracer.buildSpan("test")
    def start = System.nanoTime()
    def span = builder.start()
    def between = System.nanoTime()
    def betweenDur = System.nanoTime() - between
    span.finish()
    def total = System.nanoTime() - start

    expect:
    // Generous 5 seconds to execute this test
    Math.abs(TimeUnit.NANOSECONDS.toSeconds(span.startTime) - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())) < 5
    span.durationNano > betweenDur
    span.durationNano < total
    span.durationNano % mod > 0 // Very slim chance of a false negative.
  }

  def "starting with a timestamp disables nanotime"() {
    setup:
    def mod = TimeUnit.MILLISECONDS.toNanos(1)
    def start = System.currentTimeMillis()
    def builder = tracer.buildSpan("test")
      .withStartTimestamp(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()))
    def span = builder.start()
    def between = System.currentTimeMillis()
    def betweenDur = System.currentTimeMillis() - between
    span.finish()
    def total = Math.max(1, System.currentTimeMillis() - start)

    expect:
    // Generous 5 seconds to execute this test
    Math.abs(TimeUnit.NANOSECONDS.toSeconds(span.startTime) - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())) < 5
    span.durationNano >= TimeUnit.MILLISECONDS.toNanos(betweenDur)
    span.durationNano <= TimeUnit.MILLISECONDS.toNanos(total)
    span.durationNano % mod == 0 || span.durationNano == 1
  }

  def "stopping with a timestamp disables nanotime"() {
    setup:
    def mod = TimeUnit.MILLISECONDS.toNanos(1)
    def builder = tracer.buildSpan("test")
    def start = System.currentTimeMillis()
    def span = builder.start()
    def between = System.currentTimeMillis()
    def betweenDur = System.currentTimeMillis() - between
    span.finish(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis() + 1))
    def total = System.currentTimeMillis() - start + 1

    expect:
    // Generous 5 seconds to execute this test
    Math.abs(TimeUnit.NANOSECONDS.toSeconds(span.startTime) - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())) < 5
    span.durationNano >= TimeUnit.MILLISECONDS.toNanos(betweenDur)
    span.durationNano <= TimeUnit.MILLISECONDS.toNanos(total)
    span.durationNano % mod == 0
  }

  def "stopping with a timestamp after start time yeilds a min duration of 1"() {
    setup:
    def span = tracer.buildSpan("test").start()
    span.finish(span.startTimeMicro - 10)

    expect:
    span.durationNano == 1
  }

  def "priority sampling metric set only on root span" () {
    setup:
    def parent = tracer.buildSpan("testParent").start()
    def child1 = tracer.buildSpan("testChild1").asChildOf(parent).start()

    child1.setSamplingPriority(PrioritySampling.SAMPLER_KEEP)
    child1.context().lockSamplingPriority()
    parent.setSamplingPriority(PrioritySampling.SAMPLER_DROP)
    child1.finish()
    def child2 = tracer.buildSpan("testChild2").asChildOf(parent).start()
    child2.finish()
    parent.finish()

    expect:
    parent.context().samplingPriority == PrioritySampling.SAMPLER_KEEP
    parent.getMetrics().get(STSSpanContext.PRIORITY_SAMPLING_KEY) == PrioritySampling.SAMPLER_KEEP
    parent.getMetrics().get(STSSpanContext.SAMPLE_RATE_KEY) == 1.0
    child1.getSamplingPriority() == parent.getSamplingPriority()
    child1.getMetrics().get(STSSpanContext.PRIORITY_SAMPLING_KEY) == null
    child1.getMetrics().get(STSSpanContext.SAMPLE_RATE_KEY) == null
    child2.getSamplingPriority() == parent.getSamplingPriority()
    child2.getMetrics().get(STSSpanContext.PRIORITY_SAMPLING_KEY) == null
    child2.getMetrics().get(STSSpanContext.SAMPLE_RATE_KEY) == null
  }
}
