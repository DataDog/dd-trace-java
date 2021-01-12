package datadog.trace.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import datadog.trace.api.Config
import datadog.trace.api.DDId
import datadog.trace.common.writer.ListWriter
import datadog.trace.test.util.DDSpecification
import org.slf4j.LoggerFactory
import spock.lang.Subject
import spock.lang.Timeout

import java.util.concurrent.CountDownLatch
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
    assert trace.rootSpanWritten == false
  }

  def "single span gets added to trace and written when finished"() {
    setup:
    rootSpan.finish()
    writer.waitForTraces(1)

    expect:
    trace.finishedSpans.isEmpty()
    writer == [[rootSpan]]
    writer.traceCount.get() == 1
  }

  def "child finishes before parent"() {
    when:
    def child = tracer.buildSpan("child").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount.get() == 2

    when:
    child.finish()

    then:
    trace.pendingReferenceCount.get() == 1
    trace.finishedSpans.asList() == [child]
    writer == []

    when:
    rootSpan.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount.get() == 0
    trace.finishedSpans.isEmpty()
    writer == [[rootSpan, child]]
    writer.traceCount.get() == 1
  }

  def "parent finishes before child which holds up trace"() {
    when:
    def child = tracer.buildSpan("child").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount.get() == 2

    when:
    rootSpan.finish()

    then:
    trace.pendingReferenceCount.get() == 1
    trace.finishedSpans.asList() == [rootSpan]
    writer == []

    when:
    child.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount.get() == 0
    trace.finishedSpans.isEmpty()
    writer == [[child, rootSpan]]
    writer.traceCount.get() == 1
  }

  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  def "trace is still reported when unfinished continuation discarded"() {
    when:
    def scope = tracer.activateSpan(rootSpan)
    scope.setAsyncPropagation(true)
    scope.capture()
    scope.close()
    rootSpan.finish()

    then:
    trace.pendingReferenceCount.get() == 1
    trace.finishedSpans.asList() == [rootSpan]
    writer == []

    when: "root span buffer delay expires"
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount.get() == 1
    trace.finishedSpans.isEmpty()
    writer == [[rootSpan]]
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
    trace.pendingReferenceCount.get() == 0
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
    def properties = new Properties()
    properties.setProperty(PARTIAL_FLUSH_MIN_SPANS, "1")
    def config = Config.get(properties)
    def tracer = CoreTracer.builder().config(config).writer(writer).build()
    def rootSpan = tracer.buildSpan("root").start()
    def trace = rootSpan.context().trace
    def child1 = tracer.buildSpan("child1").asChildOf(rootSpan).start()
    def child2 = tracer.buildSpan("child2").asChildOf(rootSpan).start()

    then:
    trace.pendingReferenceCount.get() == 3

    when:
    child2.finish()

    then:
    trace.pendingReferenceCount.get() == 2
    trace.finishedSpans.asList() == [child2]
    writer == []
    writer.traceCount.get() == 0

    when:
    child1.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount.get() == 1
    trace.finishedSpans.asList() == []
    writer == [[child1, child2]]
    writer.traceCount.get() == 1

    when:
    rootSpan.finish()
    writer.waitForTraces(2)

    then:
    trace.pendingReferenceCount.get() == 0
    trace.finishedSpans.isEmpty()
    writer == [[child1, child2], [rootSpan]]
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

    when:
    child1.finish()

    then:
    trace.pendingReferenceCount.get() == 2
    trace.finishedSpans.asList() == [child1]
    writer == []
    writer.traceCount.get() == 0

    when:
    child2.finish()
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount.get() == 1
    trace.finishedSpans.isEmpty()
    writer == [[child2, child1]]
    writer.traceCount.get() == 1

    when:
    rootSpan.finish()
    writer.waitForTraces(2)

    then:
    trace.pendingReferenceCount.get() == 0
    trace.finishedSpans.isEmpty()
    writer == [[child2, child1], [rootSpan]]
    writer.traceCount.get() == 2
  }

  def "partial flush concurrency test"() {
    // reduce logging noise
    def logger = (Logger) LoggerFactory.getLogger("datadog.trace")
    def previousLevel = logger.level
    logger.setLevel(Level.OFF)

    setup:
    def latch = new CountDownLatch(1)
    def rootSpan = tracer.buildSpan("root").start()
    // Finish root span so other spans are queued automatically
    rootSpan.finish()
    PendingTrace trace = rootSpan.context().trace
    def exceptions = []

    def threads = (1..threadCount).collect {
      Thread.start {
        try {
          def spans = (1..spanCount).collect {
            tracer.startSpan("child", rootSpan.context())
          }
          latch.await()
          spans.each {
            it.finish()
          }
        } catch (Throwable ex) {
          exceptions << ex
        }
      }
    }

    expect:
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
    trace.pendingReferenceCount.get() == 0
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
}
