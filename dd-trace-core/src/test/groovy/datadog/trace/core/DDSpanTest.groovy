package datadog.trace.core

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.sampling.RateByServiceSampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.propagation.TagContext
import datadog.trace.util.test.DDSpecification

import java.util.concurrent.TimeUnit

class DDSpanTest extends DDSpecification {

  def writer = new ListWriter()
  def sampler = new RateByServiceSampler()
  def tracer = CoreTracer.builder().writer(writer).sampler(sampler).build()

  def "getters and setters"() {
    setup:
    final DDSpanContext context =
      new DDSpanContext(
        DDId.from(1),
        DDId.from(1),
        DDId.ZERO,
        "fakeService",
        "fakeOperation",
        "fakeResource",
        PrioritySampling.UNSET,
        null,
        Collections.<String, String> emptyMap(),
        false,
        "fakeType",
        null,
        PendingTrace.create(tracer, DDId.ONE),
        tracer,
        [:])

    final DDSpan span = DDSpan.create(1L, context)

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
    def span

    when:
    span = tracer.buildSpan(opName).start()
    then:
    span.getResourceName() == opName
    span.getServiceName() != ""

    when:
    final String resourceName = "fake"
    final String serviceName = "myService"
    span = CoreTracer.builder().build()
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

  def "priority sampling metric set only on root span"() {
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
    parent.getMetrics().get(DDSpanContext.PRIORITY_SAMPLING_KEY) == PrioritySampling.SAMPLER_KEEP
    child1.getSamplingPriority() == parent.getSamplingPriority()
    child1.getMetrics().get(DDSpanContext.PRIORITY_SAMPLING_KEY) == null
    child2.getSamplingPriority() == parent.getSamplingPriority()
    child2.getMetrics().get(DDSpanContext.PRIORITY_SAMPLING_KEY) == null
  }

  def "origin set only on root span"() {
    setup:
    def parent = tracer.buildSpan("testParent").asChildOf(extractedContext).start().context()
    def child = tracer.buildSpan("testChild1").asChildOf(parent).start().context()

    expect:
    parent.origin == "some-origin"
    parent.@origin == "some-origin" // Access field directly instead of getter.
    child.origin == "some-origin"
    child.@origin == null // Access field directly instead of getter.

    where:
    extractedContext                                                         | _
    new TagContext("some-origin", [:])                                       | _
    new ExtractedContext(DDId.ONE, DDId.from(2), 0, "some-origin", [:], [:]) | _
  }

  def "isRootSpan() in and not in the context of distributed tracing"() {
    setup:
    def root = tracer.buildSpan("root").asChildOf((AgentSpan.Context) extractedContext).start()
    def child = tracer.buildSpan("child").asChildOf(root).start()

    expect:
    root.isRootSpan() == isTraceRootSpan
    !child.isRootSpan()

    cleanup:
    child.finish()
    root.finish()

    where:
    extractedContext                                                         | isTraceRootSpan
    null                                                                     | true
    new ExtractedContext(DDId.from(123), DDId.from(456), 1, "789", [:], [:]) | false
  }

  def "getApplicationRootSpan() in and not in the context of distributed tracing"() {
    setup:
    def root = tracer.buildSpan("root").asChildOf((AgentSpan.Context) extractedContext).start()
    def child = tracer.buildSpan("child").asChildOf(root).start()

    expect:
    root.localRootSpan == root
    child.localRootSpan == root
    // Checking for backward compatibility method names
    root.rootSpan == root
    child.rootSpan == root

    cleanup:
    child.finish()
    root.finish()

    where:
    extractedContext                                                         | isTraceRootSpan
    null                                                                     | true
    new ExtractedContext(DDId.from(123), DDId.from(456), 1, "789", [:], [:]) | false
  }
}
