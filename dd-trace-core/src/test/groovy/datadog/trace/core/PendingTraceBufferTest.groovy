package datadog.trace.core

import datadog.trace.api.DDId
import datadog.trace.core.monitor.Monitoring
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
  def factory = new PendingTrace.Factory(tracer, bufferSpy)

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

  def "test buffer single"() {
    setup:
    buffer.start()
    def trace = factory.create(DDId.ONE)
    def latch = new CountDownLatch(1)
    def span = newSpanOf(trace)

    expect:
    !trace.rootSpanWritten.get()

    when:
    span.finish() // This should enqueue
    latch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 0
    1 * bufferSpy.enqueue(trace)
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 }) >> {
      latch.countDown()
    }
    0 * _
  }

  def "test buffer full"() {
    setup:
    // Don't start the buffer thread

    when: "Fill the buffer"
    while (buffer.queue.size() < (buffer.queue.capacity())) {
      newSpanOf(factory.create(DDId.ONE)).finish()
    }

    then:
    buffer.queue.size() == BUFFER_SIZE
    buffer.queue.capacity() * bufferSpy.enqueue(_)
    0 * _

    when:
    newSpanOf(factory.create(DDId.ONE)).finish()

    then:
    1 * bufferSpy.enqueue(_)
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 })
    0 * _
  }

  def "test quick arrival span"() {
    setup:
    def latch = new CountDownLatch(1)

    def trace = factory.create(DDId.ONE)
    def parent = newSpanOf(trace)

    when:
    parent.finish() // This should enqueue

    then:
    trace.size() == 1
    trace.pendingReferenceCount.get() == 0
    !trace.rootSpanWritten.get()
    1 * bufferSpy.enqueue(trace)
    0 * _

    when:
    def child = newSpanOf(parent)
    child.finish()

    then:
    trace.size() == 2
    trace.pendingReferenceCount.get() == 0
    !trace.rootSpanWritten.get()

    when:
    buffer.start()
    latch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount.get() == 0
    trace.rootSpanWritten.get()
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 2 }) >> {
      latch.countDown()
    }
    0 * _
  }

  def "test late arrival span"() {
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
    1 * bufferSpy.enqueue(trace)
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
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 }) >> {
      childLatch.countDown()
    }
    0 * _
  }
}
