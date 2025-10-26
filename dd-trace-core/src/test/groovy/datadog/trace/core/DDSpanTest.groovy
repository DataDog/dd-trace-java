package datadog.trace.core

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.api.TagMap
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.common.sampling.RateByServiceTraceSampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Shared

import java.util.concurrent.TimeUnit

import static datadog.trace.api.TracePropagationStyle.DATADOG
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.api.sampling.SamplingMechanism.SPAN_SAMPLING_RATE
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MAX_PER_SECOND_TAG
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_RULE_RATE_TAG

class DDSpanTest extends DDCoreSpecification {

  @Shared def writer = new ListWriter()
  @Shared def sampler = new RateByServiceTraceSampler()
  @Shared def tracer = tracerBuilder().writer(writer).sampler(sampler).build()
  @Shared def propagationTagsFactory = tracer.getPropagationTagsFactory()

  def cleanup() {
    tracer?.close()
  }

  def "getters and setters"() {
    setup:
    def span = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .withSpanType("fakeType")
      .start()

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
    span.context().lockSamplingPriority()
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
    span = tracer
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

  def "phasedFinish captures duration but doesn't publish immediately"() {
    setup:
    def mod = TimeUnit.MILLISECONDS.toNanos(1)
    def builder = tracer.buildSpan("test")
    def start = System.nanoTime()
    def span = builder.start()
    def between = System.nanoTime()
    def betweenDur = System.nanoTime() - between

    when: "calling publish before phasedFinish"
    span.publish()

    then: "has no effect"
    span.durationNano == 0
    span.context().traceCollector.pendingReferenceCount == 1
    writer.size() == 0

    when:
    def finish = span.phasedFinish()
    def total = System.nanoTime() - start

    then:
    finish
    span.context().traceCollector.pendingReferenceCount == 1
    span.context().traceCollector.spans.isEmpty()
    writer.isEmpty()

    and: "duration is recorded as negative to allow publishing"
    span.durationNano < 0
    def actualDurationNano = span.durationNano & Long.MAX_VALUE
    // Generous 5 seconds to execute this test
    Math.abs(TimeUnit.NANOSECONDS.toSeconds(span.startTime) - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())) < 5
    actualDurationNano > betweenDur
    actualDurationNano < total
    actualDurationNano % mod > 0 // Very slim chance of a false negative.

    when: "extra finishes"
    finish = span.phasedFinish()
    span.finish() // verify conflicting finishes are ignored

    then: "have no effect"
    !finish
    span.context().traceCollector.pendingReferenceCount == 1
    span.context().traceCollector.spans.isEmpty()
    writer.isEmpty()

    when:
    span.publish()

    then: "duration is flipped to positive"
    span.durationNano > 0
    span.durationNano == actualDurationNano
    span.context().traceCollector.pendingReferenceCount == 0
    writer.size() == 1

    when: "duplicate call to publish"
    span.publish()

    then: "has no effect"
    span.context().traceCollector.pendingReferenceCount == 0
    writer.size() == 1
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
    // true span duration can be <1ms if clock was about to tick over, so allow for that
    (span.durationNano % mod == 0) || (span.durationNano == 1)
  }

  def "stopping with a timestamp before start time yields a min duration of 1"() {
    setup:
    def span = tracer.buildSpan("test").start()

    // remove tick precision part of our internal time to match previous test condition
    span.finish(TimeUnit.MILLISECONDS.toMicros(TimeUnit.NANOSECONDS.toMillis(span.startTimeNano)) - 10)

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
    parent.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    parent.hasSamplingPriority()
    child1.getSamplingPriority() == parent.getSamplingPriority()
    child2.getSamplingPriority() == parent.getSamplingPriority()
    !child1.hasSamplingPriority()
    !child2.hasSamplingPriority()
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
    extractedContext                                                                                                              | _
    new TagContext("some-origin", TagMap.fromMap([:]))                                                                                            | _
    new ExtractedContext(DDTraceId.ONE, 2, PrioritySampling.SAMPLER_DROP, "some-origin", propagationTagsFactory.empty(), DATADOG) | _
  }

  def "checkRootSpan() in and not in the context of distributed tracing"() {
    setup:
    def root = tracer.buildSpan("root").asChildOf((AgentSpanContext) extractedContext).start()
    def child = tracer.buildSpan("child").asChildOf(root).start()

    expect:
    root.checkRootSpan() == isTraceRootSpan
    !child.checkRootSpan()

    cleanup:
    child.finish()
    root.finish()

    where:
    extractedContext                                                                                                              | isTraceRootSpan
    null                                                                                                                          | true
    new ExtractedContext(DDTraceId.from(123), 456, PrioritySampling.SAMPLER_KEEP, "789", propagationTagsFactory.empty(), DATADOG) | false
  }

  def "getApplicationRootSpan() in and not in the context of distributed tracing"() {
    setup:
    def root = tracer.buildSpan("root").asChildOf((AgentSpanContext) extractedContext).start()
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
    extractedContext                                                                                                              | isTraceRootSpan
    null                                                                                                                          | true
    new ExtractedContext(DDTraceId.from(123), 456, PrioritySampling.SAMPLER_KEEP, "789", propagationTagsFactory.empty(), DATADOG) | false
  }

  def 'publishing of root span closes the request context data'() {
    setup:
    def reqContextData = Mock(Closeable)
    def context = new TagContext().withRequestContextDataAppSec(reqContextData)
    def root = tracer.buildSpan("root").asChildOf(context).start()
    def child = tracer.buildSpan("child").asChildOf(root).start()

    expect:
    root.requestContext.getData(RequestContextSlot.APPSEC).is(reqContextData)
    child.requestContext.getData(RequestContextSlot.APPSEC).is(reqContextData)

    when:
    child.finish()

    then:
    0 * reqContextData.close()

    when:
    root.finish()

    then:
    1 * reqContextData.close()
  }

  def "infer top level from parent service name"() {
    setup:
    def propagationTagsFactory = tracer.getPropagationTagsFactory()
    when:
    DDSpanContext context =
      new DDSpanContext(
      DDTraceId.ONE,
      1,
      DDSpanId.ZERO,
      parentServiceName,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.<String, String> emptyMap(),
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      propagationTagsFactory.empty())
    then:
    context.isTopLevel() == expectTopLevel

    where:
    parentServiceName                     | expectTopLevel
    "foo"                                 | true
    UTF8BytesString.create("foo")         | true
    "fakeService"                         | false
    UTF8BytesString.create("fakeService") | false
    ""                                    | true
    null                                  | true
  }

  def "broken pipe exception does not create error span"() {
    when:
    def span = tracer.buildSpan("root").start()
    span.addThrowable(new IOException("Broken pipe"))
    then:
    !span.isError()
    span.getTag(DDTags.ERROR_STACK) == null
    span.getTag(DDTags.ERROR_MSG) == "Broken pipe"
  }

  def "wrapped broken pipe exception does not create error span"() {
    when:
    def span = tracer.buildSpan("root").start()
    span.addThrowable(new RuntimeException(new IOException("Broken pipe")))
    then:
    !span.isError()
    span.getTag(DDTags.ERROR_STACK) == null
    span.getTag(DDTags.ERROR_MSG) == "java.io.IOException: Broken pipe"
  }

  def "null exception safe to add"() {
    when:
    def span = tracer.buildSpan("root").start()
    span.addThrowable(null)
    then:
    !span.isError()
    span.getTag(DDTags.ERROR_STACK) == null
  }

  def "set single span sampling tags"() {
    setup:
    def span = tracer.buildSpan("testSpan").start() as DDSpan

    expect:
    span.samplingPriority() == UNSET

    when:
    span.setSpanSamplingPriority(rate, limit)

    then:
    span.getTag(SPAN_SAMPLING_MECHANISM_TAG) == SPAN_SAMPLING_RATE
    span.getTag(SPAN_SAMPLING_RULE_RATE_TAG) == rate
    span.getTag(SPAN_SAMPLING_MAX_PER_SECOND_TAG) == (limit == Integer.MAX_VALUE ? null : limit)
    // single span sampling should not change the trace sampling priority
    span.samplingPriority() == UNSET

    where:
    rate | limit
    1.0  | 10
    0.5  | 100
    0.25 | Integer.MAX_VALUE
  }

  def "error priorities should be respected"() {
    setup:
    def span = tracer.buildSpan("testSpan").start() as DDSpan

    expect:
    !span.isError()

    when:
    span.setError(true)
    then:
    span.isError()

    when:
    span.setError(false)
    then:
    !span.isError()

    when:
    span.setError(true, ErrorPriorities.HTTP_SERVER_DECORATOR)
    then:
    !span.isError()

    when:
    span.setError(true, ErrorPriorities.MANUAL_INSTRUMENTATION)
    then:
    span.isError()

    when:
    span.setError(true, Byte.MAX_VALUE)
    then:
    span.isError()
  }
}
