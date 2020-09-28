package datadog.trace.core

import datadog.common.exec.AgentTaskScheduler
import datadog.trace.api.Config
import datadog.trace.api.DDId
import datadog.trace.common.writer.ListWriter
import datadog.trace.util.gc.GCUtils
import datadog.trace.util.test.DDSpecification
import spock.lang.Subject
import spock.lang.Timeout

import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_MIN_SPANS

class PendingTraceTest extends DDSpecification {

  def writer = new ListWriter()
  def tracer = CoreTracer.builder().writer(writer).build()

  DDId traceId = DDId.from(System.identityHashCode(this))

  @Subject
  PendingTrace trace = tracer.pendingTraceFactory.create(traceId)

  DDSpan rootSpan = SpanFactory.newSpanOf(trace)

  def setup() {
    assert trace.size() == 0
    assert trace.pendingReferenceCount.get() == 1
    assert trace.weakSpans.size() == 1
    assert trace.weakContinuations.size() == 0
    assert trace.isWritten.get() == false
    assert scheduled(trace)
  }

  def "single span gets added to trace and written when finished"() {
    setup:
    rootSpan.finish()
    writer.waitForTraces(1)

    expect:
    trace.asList() == [rootSpan]
    writer == [[rootSpan]]
    writer.traceCount.get() == 1
  }

  def "child finishes before parent"() {
    when:
    def child = tracer.buildSpan("child").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount.get() == 2
    trace.weakSpans.size() == 2

    when:
    child.finish()

    then:
    trace.pendingReferenceCount.get() == 1
    trace.weakSpans.size() == 1
    trace.asList() == [child]
    writer == []

    when:
    rootSpan.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount.get() == 0
    trace.weakSpans.size() == 0
    trace.asList() == [rootSpan, child]
    writer == [[rootSpan, child]]
    writer.traceCount.get() == 1
  }

  def "parent finishes before child which holds up trace"() {
    when:
    def child = tracer.buildSpan("child").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount.get() == 2
    trace.weakSpans.size() == 2

    when:
    rootSpan.finish()

    then:
    trace.pendingReferenceCount.get() == 1
    trace.weakSpans.size() == 1
    trace.asList() == [rootSpan]
    writer == []

    when:
    child.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount.get() == 0
    trace.weakSpans.size() == 0
    trace.asList() == [child, rootSpan]
    writer == [[child, rootSpan]]
    writer.traceCount.get() == 1
  }

  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  def "trace does not report when unfinished span discarded"() {
    when:
    def child = tracer.buildSpan("child").asChildOf(rootSpan).start()
    rootSpan.finish()

    then:
    trace.pendingReferenceCount.get() == 1
    trace.weakSpans.size() == 1
    trace.asList() == [rootSpan]
    writer == []

    when:
    def childRef = new WeakReference<>(child)
    child = null
    GCUtils.awaitGC(childRef)
    while (trace.pendingReferenceCount.get() > 0) {
      trace.clean()
    }

    then:
    trace.pendingReferenceCount.get() == 0
    trace.weakSpans.size() == 0
    trace.asList() == [rootSpan]
    writer == []
    writer.traceCount.get() == 1
    scheduled(trace) // Doesn't get unscheduled until GC'd
  }

  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  def "trace is still reported when unfinished continuation discarded"() {
    when:
    def scope = tracer.activateSpan(rootSpan)
    scope.setAsyncPropagation(true)
    def continuationRef = new WeakReference<>(scope.capture())
    scope.close()
    rootSpan.finish()

    then:
    trace.pendingReferenceCount.get() == 1
    trace.weakSpans.size() == 0
    trace.weakContinuations.size() == 1
    trace.asList() == [rootSpan]
    writer == []

    when:
    GCUtils.awaitGC(continuationRef)
    while (trace.pendingReferenceCount.get() > 0) {
      trace.clean()
    }
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount.get() == 0
    trace.weakSpans.size() == 0
    trace.asList() == [rootSpan]
    writer == [[rootSpan]]
    writer.traceCount.get() == 1
    scheduled(trace) // Doesn't get unscheduled until GC'd
  }

  def "add unfinished span to trace fails"() {
    setup:
    trace.addSpan(rootSpan)

    expect:
    trace.pendingReferenceCount.get() == 1
    trace.weakSpans.size() == 1
    trace.asList() == []
    writer.traceCount.get() == 0
  }

  def "register span to wrong trace fails"() {
    setup:
    def otherTrace = tracer.pendingTraceFactory.create(DDId.from(traceId.toLong() - 10))
    otherTrace.registerSpan(new DDSpan(0, rootSpan.context()))

    expect:
    otherTrace.pendingReferenceCount.get() == 0
    otherTrace.weakSpans.size() == 0
    otherTrace.asList() == []
  }

  def "add span to wrong trace fails"() {
    setup:
    def otherTrace = tracer.pendingTraceFactory.create(DDId.from(traceId.toLong() - 10))
    rootSpan.finish()
    otherTrace.addSpan(rootSpan)

    expect:
    otherTrace.pendingReferenceCount.get() == 0
    otherTrace.weakSpans.size() == 0
    otherTrace.asList() == []
  }


  def "child spans created after trace written"() {
    setup:
    rootSpan.finish()
    writer.waitForTraces(1)
    // this shouldn't happen, but it's possible users of the api
    // may incorrectly add spans after the trace is reported.
    // in those cases we should still decrement the pending trace count
    DDSpan childSpan = tracer.buildSpan("child").asChildOf(rootSpan).start()
    childSpan.finish()

    expect:
    trace.pendingReferenceCount.get() == 0
    trace.asList() == [rootSpan]
    writer == [[rootSpan]]
  }

  def "child spans created quickly after trace written"() {
    setup:
    rootSpan.finish()
    // this shouldn't happen, but it's possible users of the api
    // may incorrectly add spans after the trace is reported.
    // in those cases we should still decrement the pending trace count
    DDSpan childSpan = tracer.buildSpan("child").asChildOf(rootSpan).start()
    childSpan.finish()
    writer.waitForTraces(1)

    expect:
    trace.pendingReferenceCount.get() == 0
    trace.asList() == [childSpan, rootSpan]
    writer == [[childSpan, rootSpan]]
  }

  def "test getCurrentTimeNano"() {
    expect:
    // Generous 5 seconds to execute this test
    Math.abs(TimeUnit.NANOSECONDS.toSeconds(trace.currentTimeNano) - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())) < 5
  }

  def "partial flush"() {
    when:
    def properties = new Properties()
    properties.setProperty(PARTIAL_FLUSH_MIN_SPANS, "1")
    def config = Config.get(properties)
    def tracer = CoreTracer.builder().config(config).writer(writer).build()
    def trace = tracer.pendingTraceFactory.create(traceId)
    def rootSpan = SpanFactory.newSpanOf(trace)
    def child1 = tracer.buildSpan("child1").asChildOf(rootSpan).start()
    def child2 = tracer.buildSpan("child2").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount.get() == 3
    trace.weakSpans.size() == 3

    when:
    rootSpan.finish()

    then:
    trace.pendingReferenceCount.get() == 2
    trace.weakSpans.size() == 2
    trace.asList() == [rootSpan]
    writer == []
    writer.traceCount.get() == 0

    when:
    child1.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount.get() == 1
    trace.weakSpans.size() == 1
    trace.asList() == [rootSpan]
    writer == [[child1]]
    writer.traceCount.get() == 1

    when:
    child2.finish()
    writer.waitForTraces(2)

    then:
    trace.pendingReferenceCount.get() == 0
    trace.weakSpans.size() == 0
    trace.asList() == [child2, rootSpan]
    writer == [[child1], [child2, rootSpan]]
    writer.traceCount.get() == 2
  }

  def "partial flush with root span closed last"() {
    when:
    def properties = new Properties()
    properties.setProperty(PARTIAL_FLUSH_MIN_SPANS, "1")
    def config = Config.get(properties)
    def tracer = CoreTracer.builder().config(config).writer(writer).build()
    def trace = tracer.pendingTraceFactory.create(traceId)
    def rootSpan = SpanFactory.newSpanOf(trace)
    def child1 = tracer.buildSpan("child1").asChildOf(rootSpan).start()
    def child2 = tracer.buildSpan("child2").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount.get() == 3
    trace.weakSpans.size() == 3

    when:
    child1.finish()

    then:
    trace.pendingReferenceCount.get() == 2
    trace.weakSpans.size() == 2
    trace.asList() == [child1]
    writer == []
    writer.traceCount.get() == 0

    when:
    child2.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount.get() == 1
    trace.weakSpans.size() == 1
    trace.asList() == []
    writer == [[child2, child1]]
    writer.traceCount.get() == 1

    when:
    rootSpan.finish()
    writer.waitForTraces(2)

    then:
    trace.pendingReferenceCount.get() == 0
    trace.weakSpans.size() == 0
    trace.asList() == [rootSpan]
    writer == [[child2, child1], [rootSpan]]
    writer.traceCount.get() == 2
  }

  boolean scheduled(PendingTrace trace) {
    // This might be racy if the task is in progress and not rescheduled.
    return AgentTaskScheduler.INSTANCE.workQueue.any { task ->
      task.target instanceof WeakReference && task.target.get() == trace
    }
  }
}
