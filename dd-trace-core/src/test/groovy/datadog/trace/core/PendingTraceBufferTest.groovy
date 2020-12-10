package datadog.trace.core

import com.timgroup.statsd.NoOpStatsDClient
import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import datadog.trace.context.TraceScope
import datadog.trace.core.jfr.DDNoopScopeEventFactory
import datadog.trace.core.monitor.Monitoring
import datadog.trace.core.scopemanager.ContinuableScopeManager
import datadog.trace.test.util.DDSpecification
import spock.lang.Subject
import spock.lang.Timeout

import java.util.concurrent.CountDownLatch

import static datadog.trace.core.PendingTraceBuffer.BUFFER_SIZE

@Timeout(5)
class PendingTraceBufferTest extends DDSpecification {
  @Subject
  def buffer = new PendingTraceBuffer()
  def bufferSpy = Spy(buffer)

  def tracer = Mock(CoreTracer)
  def scopeManager = new ContinuableScopeManager(10, new DDNoopScopeEventFactory(), new NoOpStatsDClient(), true, true)
  def factory = new PendingTrace.Factory(tracer, bufferSpy)
  List<TraceScope.Continuation> continuations = []

  def cleanup() {
    buffer.close()
    buffer.worker.join(1000)
  }

  def "test buffer lifecycle"() {
    expect:
    !buffer.worker.alive

    when:
    buffer.start()

    then:
    buffer.worker.alive
    buffer.worker.daemon

    when: "start called again"
    buffer.start()

    then:
    thrown IllegalThreadStateException
    buffer.worker.alive
    buffer.worker.daemon

    when:
    buffer.close()
    buffer.worker.join(1000)

    then:
    !buffer.worker.alive
  }

  def "continuation buffers root"() {
    setup:
    def trace = factory.create(DDId.ONE)
    def span = newSpanOf(trace)

    expect:
    !trace.rootSpanWritten

    when:
    addContinuation(span)
    span.finish() // This should enqueue

    then:
    continuations.size() == 1
    trace.pendingReferenceCount.get() == 1
    1 * bufferSpy.enqueue(trace)
    _ * tracer.getPartialFlushMinSpans() >> 10
    0 * _

    when:
    continuations[0].cancel()

    then:
    trace.pendingReferenceCount.get() == 0
    1 * tracer.write({ it.size() == 1 })
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    _ * tracer.getPartialFlushMinSpans() >> 10
    0 * _
  }

  def "unfinished child buffers root"() {
    setup:
    def trace = factory.create(DDId.ONE)
    def parent = newSpanOf(trace)
    def child = newSpanOf(parent)

    expect:
    !trace.rootSpanWritten

    when:
    parent.finish() // This should enqueue

    then:
    trace.size() == 1
    trace.pendingReferenceCount.get() == 1
    1 * bufferSpy.enqueue(trace)
    _ * tracer.getPartialFlushMinSpans() >> 10
    0 * _

    when:
    child.finish()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 0
    1 * tracer.write({ it.size() == 2 })
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    _ * tracer.getPartialFlushMinSpans() >> 10
    0 * _
  }

  def "buffer full yields immediate write"() {
    setup:
    // Don't start the buffer thread

    when: "Fill the buffer"
    while (buffer.queue.size() < (buffer.queue.capacity())) {
      addContinuation(newSpanOf(factory.create(DDId.ONE))).finish()
    }

    then:
    buffer.queue.size() == BUFFER_SIZE
    buffer.queue.capacity() * bufferSpy.enqueue(_)
    _ * tracer.getPartialFlushMinSpans() >> 10
    _ * tracer.mapServiceName(_)
    0 * _

    when:
    addContinuation(newSpanOf(factory.create(DDId.ONE))).finish()

    then:
    1 * bufferSpy.enqueue(_)
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 })
    _ * tracer.getPartialFlushMinSpans() >> 10
    _ * tracer.mapServiceName(_)
    0 * _
  }

  def "continuation allows adding after root finished"() {
    setup:
    def latch = new CountDownLatch(1)

    def trace = factory.create(DDId.ONE)
    def parent = addContinuation(newSpanOf(trace))
    TraceScope.Continuation continuation = continuations[0]

    expect:
    continuations.size() == 1

    when:
    parent.finish() // This should enqueue

    then:
    trace.size() == 1
    trace.pendingReferenceCount.get() == 1
    !trace.rootSpanWritten
    1 * bufferSpy.enqueue(trace)
    _ * tracer.getPartialFlushMinSpans() >> 10
    0 * _

    when:
    def child = newSpanOf(parent)
    child.finish()

    then:
    trace.size() == 2
    trace.pendingReferenceCount.get() == 1
    !trace.rootSpanWritten

    when:
    buffer.start()
    continuation.cancel()
    latch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 0
    trace.rootSpanWritten
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 2 }) >> {
      latch.countDown()
    }
    _ * tracer.getPartialFlushMinSpans() >> 10
    0 * _
  }

  def "late arrival span requeues pending trace"() {
    setup:
    buffer.start()
    def parentLatch = new CountDownLatch(1)
    def childLatch = new CountDownLatch(1)

    def trace = factory.create(DDId.ONE)
    def parent = newSpanOf(trace)

    when:
    parent.finish() // This should enqueue
    parentLatch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 0
    trace.rootSpanWritten
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 }) >> {
      parentLatch.countDown()
    }
    _ * tracer.getPartialFlushMinSpans() >> 10
    0 * _

    when:
    def child = newSpanOf(parent)
    child.finish()
    childLatch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 0
    trace.rootSpanWritten
    1 * bufferSpy.enqueue(trace)
    _ * tracer.getPartialFlushMinSpans() >> 10
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 }) >> {
      childLatch.countDown()
    }
    _ * tracer.mapServiceName(_)
    0 * _
  }

  def "flush clears the buffer"() {
    setup:
    // Don't start the buffer thread
    def trace = factory.create(DDId.ONE)
    def parent = newSpanOf(trace)
    def child = newSpanOf(parent)

    when:
    parent.finish() // This should enqueue

    then:
    trace.size() == 1
    trace.pendingReferenceCount.get() == 1
    !trace.rootSpanWritten
    1 * bufferSpy.enqueue(trace)
    _ * tracer.getPartialFlushMinSpans() >> 10
    0 * _

    when:
    buffer.flush()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 1
    trace.rootSpanWritten
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 })
    0 * _

    when:
    child.finish()

    then:
    trace.size() == 1
    trace.pendingReferenceCount.get() == 0
    trace.rootSpanWritten
    _ * tracer.getPartialFlushMinSpans() >> 10
    1 * bufferSpy.enqueue(trace)
    0 * _
  }

  def addContinuation(DDSpan span) {
    def scope = scopeManager.activate(span, ScopeSource.INSTRUMENTATION, true)
    continuations << scope.capture()
    scope.close()
    return span
  }

  static DDSpan newSpanOf(PendingTrace trace) {
    def context = new DDSpanContext(
      trace.traceId,
      DDId.from(1),
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      trace)
    return DDSpan.create(0, context)
  }

  static DDSpan newSpanOf(DDSpan parent) {
    def trace = parent.context().trace
    def context = new DDSpanContext(
      trace.traceId,
      DDId.from(2),
      parent.context().spanId,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      trace)
    return DDSpan.create(0, context)
  }
}
