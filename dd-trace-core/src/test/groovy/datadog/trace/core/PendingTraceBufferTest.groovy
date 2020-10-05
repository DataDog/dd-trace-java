package datadog.trace.core

import com.timgroup.statsd.NoOpStatsDClient
import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import datadog.trace.context.TraceScope
import datadog.trace.core.jfr.DDNoopScopeEventFactory
import datadog.trace.core.monitor.Monitoring
import datadog.trace.core.scopemanager.ContinuableScopeManager
import datadog.trace.util.test.DDSpecification
import spock.lang.Subject
import spock.lang.Timeout

import java.util.concurrent.CountDownLatch

import static datadog.trace.core.PendingTraceBuffer.BUFFER_SIZE
import static datadog.trace.core.SpanFactory.newSpanOf

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
    def latch = new CountDownLatch(1)
    def span = newSpanOf(trace)

    expect:
    !trace.rootSpanWritten.get()

    when:
    addContinuation(span)
    span.finish() // This should enqueue

    then:
    continuations.size() == 1
    trace.pendingReferenceCount.get() == 1
    1 * bufferSpy.enqueue(trace)
    0 * _

    when:
    continuations[0].cancel()

    then:
    trace.pendingReferenceCount.get() == 0
    1 * tracer.getPartialFlushMinSpans() >> 10
    0 * _

    when:
    buffer.start()
    latch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 0
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 }) >> {
      latch.countDown()
    }
    0 * _
  }

  def "unfinished child buffers root"() {
    setup:
    def trace = factory.create(DDId.ONE)
    def latch = new CountDownLatch(1)
    def parent = newSpanOf(trace)
    def child = newSpanOf(parent)

    expect:
    !trace.rootSpanWritten.get()

    when:
    parent.finish() // This should enqueue

    then:
    trace.size() == 1
    trace.pendingReferenceCount.get() == 1
    1 * bufferSpy.enqueue(trace)
    0 * _

    when:
    child.finish()

    then:
    trace.size() == 2
    trace.pendingReferenceCount.get() == 0
    1 * tracer.getPartialFlushMinSpans() >> 10
    0 * _

    when:
    buffer.start()
    latch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 0
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 2 }) >> {
      latch.countDown()
    }
    0 * _
  }

  def "buffer full yeilds immediate write"() {
    setup:
    // Don't start the buffer thread

    when: "Fill the buffer"
    while (buffer.queue.size() < (buffer.queue.capacity())) {
      addContinuation(newSpanOf(factory.create(DDId.ONE))).finish()
    }

    then:
    buffer.queue.size() == BUFFER_SIZE
    buffer.queue.capacity() * bufferSpy.enqueue(_)
    0 * _

    when:
    addContinuation(newSpanOf(factory.create(DDId.ONE))).finish()

    then:
    1 * bufferSpy.enqueue(_)
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 })
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
    !trace.rootSpanWritten.get()
    1 * bufferSpy.enqueue(trace)
    0 * _

    when:
    def child = newSpanOf(parent)
    child.finish()

    then:
    trace.size() == 2
    trace.pendingReferenceCount.get() == 1
    !trace.rootSpanWritten.get()

    when:
    buffer.start()
    continuation.cancel()
    latch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 0
    trace.rootSpanWritten.get()
    1 * tracer.getPartialFlushMinSpans() >> 10
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 2 }) >> {
      latch.countDown()
    }
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
    trace.rootSpanWritten.get()
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 }) >> {
      parentLatch.countDown()
    }
    0 * _

    when:
    def child = newSpanOf(parent)
    child.finish()
    childLatch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 0
    trace.rootSpanWritten.get()
    1 * bufferSpy.enqueue(trace)
    1 * tracer.getPartialFlushMinSpans() >> 10
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 }) >> {
      childLatch.countDown()
    }
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
    !trace.rootSpanWritten.get()
    1 * bufferSpy.enqueue(trace)
    0 * _

    when:
    buffer.flush()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 1
    trace.rootSpanWritten.get()
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 })
    0 * _

    when:
    child.finish()

    then:
    trace.size() == 1
    trace.pendingReferenceCount.get() == 0
    trace.rootSpanWritten.get()
    1 * tracer.getPartialFlushMinSpans() >> 10
    1 * bufferSpy.enqueue(trace)
    0 * _
  }

  def addContinuation(DDSpan span) {
    def scope = scopeManager.activate(span, ScopeSource.INSTRUMENTATION, true)
    continuations << scope.capture()
    scope.close()
    return span
  }
}
