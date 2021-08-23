package datadog.trace.core

import datadog.trace.api.Checkpointer
import datadog.trace.api.DDId
import datadog.trace.api.DDTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.common.sampling.RateByServiceSampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.propagation.TagContext
import datadog.trace.core.test.DDCoreSpecification

import java.util.concurrent.TimeUnit

import static datadog.trace.api.Checkpointer.CPU
import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION

class DDSpanTest extends DDCoreSpecification {

  def writer = new ListWriter()
  def sampler = new RateByServiceSampler()
  def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

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
    span.context().trace.pendingReferenceCount == 1
    writer.size() == 0

    when:
    def finish = span.phasedFinish()
    def total = System.nanoTime() - start

    then:
    finish
    span.context().trace.pendingReferenceCount == 1
    span.context().trace.finishedSpans.isEmpty()
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
    span.context().trace.pendingReferenceCount == 1
    span.context().trace.finishedSpans.isEmpty()
    writer.isEmpty()

    when:
    span.publish()

    then: "duration is flipped to positive"
    span.durationNano > 0
    span.durationNano == actualDurationNano
    span.context().trace.pendingReferenceCount == 0
    writer.size() == 1

    when: "duplicate call to publish"
    span.publish()

    then: "has no effect"
    span.context().trace.pendingReferenceCount == 0
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
    extractedContext                                                                                       | _
    new TagContext("some-origin", null, null, null, null, null, [:])                                       | _
    new ExtractedContext(DDId.ONE, DDId.from(2), 0, "some-origin", null, null, null, null, null, [:], [:]) | _
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
    extractedContext                                                                                       | isTraceRootSpan
    null                                                                                                   | true
    new ExtractedContext(DDId.from(123), DDId.from(456), 1, "789", null, null, null, null, null, [:], [:]) | false
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
    extractedContext                                                                                       | isTraceRootSpan
    null                                                                                                   | true
    new ExtractedContext(DDId.from(123), DDId.from(456), 1, "789", null, null, null, null, null, [:], [:]) | false
  }

  def "infer top level from parent service name"() {
    when:
    DDSpanContext context =
      new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
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
      tracer.pendingTraceFactory.create(DDId.ONE),
      null)
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

  def "span start and finish emit checkpoints"() {
    setup:
    Checkpointer checkpointer = Mock()
    tracer.registerCheckpointer(checkpointer)
    DDSpanContext context =
      new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.<String, String> emptyMap(),
      false,
      "fakeType",
      0,
      tracer.pendingTraceFactory.create(DDId.ONE),
      null)

    def span = null

    when:
    span = DDSpan.create(1, context)
    then:
    // can not assert against 'span' as this check seems to operate on 'span' value before it has been created
    1 * checkpointer.checkpoint(_, SPAN)

    when:
    span.startThreadMigration()
    then:
    1 * checkpointer.checkpoint(span, THREAD_MIGRATION)
    when:
    span.finishThreadMigration()
    then:
    1 * checkpointer.checkpoint(span, THREAD_MIGRATION | END)

    when:
    span.finishWork()
    then:
    1 * checkpointer.checkpoint(span, CPU | END)

    when:
    span.finish()
    then:
    1 * checkpointer.checkpoint(span, SPAN | END)
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

  def "null exception safe to add"() {
    when:
    def span = tracer.buildSpan("root").start()
    span.addThrowable(null)
    then:
    !span.isError()
    span.getTag(DDTags.ERROR_STACK) == null
  }

  def "checkpointing set only on root span"() {
    setup:
    def parent = tracer.buildSpan("testRoot").start()
    def child = tracer.buildSpan("testSpan").asChildOf(parent).start()

    when:
    child.setEmittingCheckpoints(true)

    then:
    parent.isEmittingCheckpoints() == true
    parent.@emittingCheckpoints == true // Access field directly instead of getter.
    parent.getTag(DDSpan.CHECKPOINTED_TAG) == true
    child.isEmittingCheckpoints() == true // flag is reflected in children
    child.@emittingCheckpoints == null // but no value is stored in the field
    child.getTag(DDSpan.CHECKPOINTED_TAG) == null // child span does not get the tag set
  }
}
