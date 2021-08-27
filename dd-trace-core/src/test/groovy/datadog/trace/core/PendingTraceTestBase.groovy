package datadog.trace.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import datadog.trace.api.Checkpointer
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import org.slf4j.LoggerFactory

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_MIN_SPANS

abstract class PendingTraceTestBase extends DDCoreSpecification {

  def writer = new ListWriter()
  def tracer = tracerBuilder().writer(writer).build()

  DDSpan rootSpan = tracer.buildSpan("fakeOperation").start()
  PendingTrace trace = rootSpan.context().trace

  def setup() {
    assert trace.size() == 0
    assert trace.pendingReferenceCount == 1
    assert trace.rootSpanWritten == false
  }

  def cleanup() {
    tracer?.close()
  }

  def "single span gets added to trace and written when finished"() {
    when:
    rootSpan.finish()
    writer.waitForTraces(1)

    then:
    trace.finishedSpans.isEmpty()
    writer == [[rootSpan]]
    writer.traceCount.get() == 1
  }

  def "child finishes before parent"() {
    when:
    def child = tracer.buildSpan("child").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount == 2

    when:
    child.finish()

    then:
    trace.pendingReferenceCount == 1
    trace.finishedSpans.asList() == [child]
    writer == []

    when:
    rootSpan.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount == 0
    trace.finishedSpans.isEmpty()
    writer == [[rootSpan, child]]
    writer.traceCount.get() == 1
  }

  def "parent finishes before child which holds up trace"() {
    when:
    def child = tracer.buildSpan("child").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount == 2

    when:
    rootSpan.finish()

    then:
    trace.pendingReferenceCount == 1
    trace.finishedSpans.asList() == [rootSpan]
    writer == []

    when:
    child.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount == 0
    trace.finishedSpans.isEmpty()
    writer == [[child, rootSpan]]
    writer.traceCount.get() == 1
  }

  def "child spans created after trace written reported separately"() {
    setup:
    rootSpan.finish()
    // this shouldn't happen, but it's possible users of the api
    // may incorrectly add spans after the trace is reported.
    // in those cases we should still decrement the pending trace count
    DDSpan childSpan = tracer.buildSpan("child").asChildOf(rootSpan).start()
    childSpan.finish()
    writer.waitForTraces(2)

    expect:
    trace.pendingReferenceCount == 0
    trace.finishedSpans.isEmpty()
    writer == [[rootSpan], [childSpan]]
  }

  def "test getCurrentTimeNano"() {
    expect:
    // Generous 5 seconds to execute this test
    Math.abs(TimeUnit.NANOSECONDS.toSeconds(trace.currentTimeNano) - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())) < 5
  }

  def "partial flush"() {
    when:
    injectSysConfig(PARTIAL_FLUSH_MIN_SPANS, "1")
    def quickTracer = tracerBuilder().writer(writer).build()
    def rootSpan = quickTracer.buildSpan("root").start()
    def trace = rootSpan.context().trace
    def child1 = quickTracer.buildSpan("child1").asChildOf(rootSpan).start()
    def child2 = quickTracer.buildSpan("child2").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount == 3

    when:
    child2.finish()

    then:
    trace.pendingReferenceCount == 2
    trace.finishedSpans.asList() == [child2]
    writer == []
    writer.traceCount.get() == 0

    when:
    child1.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount == 1
    trace.finishedSpans.asList() == []
    writer == [[child1, child2]]
    writer.traceCount.get() == 1

    when:
    rootSpan.finish()
    writer.waitForTraces(2)

    then:
    trace.pendingReferenceCount == 0
    trace.finishedSpans.isEmpty()
    writer == [[child1, child2], [rootSpan]]
    writer.traceCount.get() == 2

    cleanup:
    quickTracer.close()
  }

  def "partial flush with root span closed last"() {
    when:
    injectSysConfig(PARTIAL_FLUSH_MIN_SPANS, "1")
    def quickTracer = tracerBuilder().writer(writer).build()
    def rootSpan = quickTracer.buildSpan("root").start()
    def trace = rootSpan.context().trace
    def child1 = quickTracer.buildSpan("child1").asChildOf(rootSpan).start()
    def child2 = quickTracer.buildSpan("child2").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount == 3

    when:
    child1.finish()

    then:
    trace.pendingReferenceCount == 2
    trace.finishedSpans.asList() == [child1]
    writer == []
    writer.traceCount.get() == 0

    when:
    child2.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount == 1
    trace.finishedSpans.isEmpty()
    writer == [[child2, child1]]
    writer.traceCount.get() == 1

    when:
    rootSpan.finish()
    writer.waitForTraces(2)

    then:
    trace.pendingReferenceCount == 0
    trace.finishedSpans.isEmpty()
    writer == [[child2, child1], [rootSpan]]
    writer.traceCount.get() == 2

    cleanup:
    quickTracer.close()
  }

  def "partial flush concurrency test"() {
    // reduce logging noise
    def logger = (Logger) LoggerFactory.getLogger("datadog.trace")
    def previousLevel = logger.level
    logger.setLevel(Level.OFF)

    setup:
    def latch = new CountDownLatch(1)
    def rootSpan = tracer.buildSpan("root").start()
    PendingTrace trace = rootSpan.context().trace
    def exceptions = []
    def threads = (1..threadCount).collect {
      Thread.start {
        try {
          latch.await()
          def spans = (1..spanCount).collect {
            tracer.startSpan("child", rootSpan.context())
          }
          spans.each {
            it.finish()
          }
        } catch (Throwable ex) {
          exceptions << ex
        }
      }
    }

    when:
    // Finish root span so other spans are queued automatically
    rootSpan.finish()

    then:
    writer.waitForTraces(1)

    when:
    latch.countDown()
    threads.each {
      it.join()
    }
    trace.pendingTraceBuffer.flush()
    logger.setLevel(previousLevel)

    then:
    exceptions.isEmpty()
    trace.pendingReferenceCount == 0
    writer.sum { it.size() } == threadCount * spanCount + 1

    cleanup:
    logger.setLevel(previousLevel)

    where:
    threadCount | spanCount
    1           | 1
    2           | 1
    1           | 2
    // Sufficiently large to fill the buffer:
    5           | 2000
    10          | 1000
    50          | 500
  }

  def "start and finish span emits checkpoints from PendingTrace"() {
    // this test is kept close to pending trace despite not exercising
    // its methods directly because this is where the reponsibility lies
    // for emitting the checkpoints, and this is the least surprising place
    // for a test to fail when modifying PendingTrace
    setup:
    Checkpointer checkpointer = Mock()
    tracer.registerCheckpointer(checkpointer)

    // unfortunately the span can't be
    when:
    def span = tracer.buildSpan("test").start()
    then:
    1 * checkpointer.checkpoint(_, SPAN)

    when:
    span.finish()
    then:
    1 * checkpointer.checkpoint(_, SPAN | END)
  }
}
