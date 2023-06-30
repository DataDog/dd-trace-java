package datadog.trace.core

import datadog.trace.api.Config
import datadog.communication.monitor.Monitoring
import datadog.trace.SamplingPriorityMetadataChecker
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.time.SystemTimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopPathwayContext
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import datadog.trace.context.TraceScope
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.scopemanager.ContinuableScopeManager
import datadog.trace.test.util.DDSpecification
import spock.lang.Subject
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.core.PendingTraceBuffer.BUFFER_SIZE

@Timeout(5)
class PendingTraceBufferTest extends DDSpecification {
  @Subject
  def buffer = PendingTraceBuffer.delaying(SystemTimeSource.INSTANCE, Mock(Config), null, null)
  def bufferSpy = Spy(buffer)

  def tracer = Mock(CoreTracer)
  def traceConfig = Mock(CoreTracer.ConfigSnapshot)
  def scopeManager = new ContinuableScopeManager(10, true, true)
  def factory = new PendingTrace.Factory(tracer, bufferSpy, SystemTimeSource.INSTANCE, false, HealthMetrics.NO_OP)
  List<TraceScope.Continuation> continuations = []

  def setup() {
    tracer.captureTraceConfig() >> traceConfig
    traceConfig.getServiceMapping() >> [:]
  }

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
    def trace = factory.create(DDTraceId.ONE)
    def span = newSpanOf(trace)

    expect:
    !trace.rootSpanWritten

    when:
    addContinuation(span)
    span.finish() // This should enqueue

    then:
    continuations.size() == 1
    trace.pendingReferenceCount == 1
    1 * bufferSpy.longRunningSpansEnabled()
    1 * bufferSpy.enqueue(trace)
    _ * tracer.getPartialFlushMinSpans() >> 10
    1 * tracer.getTimeWithNanoTicks(_)
    0 * _

    when:
    continuations[0].cancel()

    then:
    trace.pendingReferenceCount == 0
    1 * tracer.write({ it.size() == 1 })
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    _ * tracer.getPartialFlushMinSpans() >> 10
    0 * _
  }

  def "unfinished child buffers root"() {
    setup:
    def trace = factory.create(DDTraceId.ONE)
    def parent = newSpanOf(trace)
    def child = newSpanOf(parent)

    expect:
    !trace.rootSpanWritten

    when:
    parent.finish() // This should enqueue

    then:
    trace.size() == 1
    trace.pendingReferenceCount == 1
    1 * bufferSpy.enqueue(trace)
    _ * bufferSpy.longRunningSpansEnabled()
    _ * tracer.getPartialFlushMinSpans() >> 10
    1 * tracer.getTimeWithNanoTicks(_)
    0 * _

    when:
    child.finish()

    then:
    trace.size() == 0
    trace.pendingReferenceCount == 0
    _ * bufferSpy.longRunningSpansEnabled()
    1 * tracer.write({ it.size() == 2 })
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    _ * tracer.getPartialFlushMinSpans() >> 10
    1 * tracer.getTimeWithNanoTicks(_)
    0 * _
  }

  def "priority sampling is always sent"() {
    setup:
    def parent = addContinuation(newSpanOf(factory.create(DDTraceId.ONE), PrioritySampling.USER_KEEP))
    def metadataChecker = new SamplingPriorityMetadataChecker()

    when: "Fill the buffer - Only children - Priority taken from root"

    for (int i = 0; i < 11; i++) {
      newSpanOf(parent).finish()
    }

    then:
    _ * tracer.getPartialFlushMinSpans() >> 10
    _ * traceConfig.getServiceMapping() >> [:]
    _ * tracer.getTimeWithNanoTicks(_)
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    _ * bufferSpy.longRunningSpansEnabled()
    1 * tracer.write(_) >> { List<List<DDSpan>> spans ->
      spans.first().first().processTagsAndBaggage(metadataChecker)
    }
    0 *  _
    metadataChecker.hasSamplingPriority
  }

  def "buffer full yields immediate write"() {
    setup:
    // Don't start the buffer thread

    when: "Fill the buffer"
    for (i in  1..buffer.queue.capacity()) {
      addContinuation(newSpanOf(factory.create(DDTraceId.ONE))).finish()
    }

    then:
    _ * tracer.captureTraceConfig() >> traceConfig
    buffer.queue.size() == BUFFER_SIZE
    buffer.queue.capacity() * bufferSpy.enqueue(_)
    _ * bufferSpy.longRunningSpansEnabled()
    _ * tracer.getPartialFlushMinSpans() >> 10
    _ * traceConfig.getServiceMapping() >> [:]
    _ * tracer.getTimeWithNanoTicks(_)
    0 * _

    when:
    def pendingTrace = factory.create(DDTraceId.ONE)
    addContinuation(newSpanOf(pendingTrace)).finish()

    then:
    1 * tracer.captureTraceConfig() >> traceConfig
    1 * bufferSpy.enqueue(_)
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    _ * bufferSpy.longRunningSpansEnabled()
    1 * tracer.write({ it.size() == 1 })
    _ * tracer.getPartialFlushMinSpans() >> 10
    _ * traceConfig.getServiceMapping() >> [:]
    2 * tracer.getTimeWithNanoTicks(_)
    0 * _
    pendingTrace.isEnqueued == 0
  }

  def "long-running trace: buffer full does not trigger write"() {
    setup:
    // Don't start the buffer thread

    when: "Fill the buffer"
    for (i in  1..buffer.queue.capacity()) {
      addContinuation(newSpanOf(factory.create(DDTraceId.ONE))).finish()
    }

    then:
    _ * tracer.captureTraceConfig() >> traceConfig
    buffer.queue.size() == BUFFER_SIZE
    buffer.queue.capacity() * bufferSpy.enqueue(_)
    _ * bufferSpy.longRunningSpansEnabled()
    _ * tracer.getPartialFlushMinSpans() >> 10
    _ * traceConfig.getServiceMapping() >> [:]
    _ * tracer.getTimeWithNanoTicks(_)
    0 * _

    when:
    def pendingTrace = factory.create(DDTraceId.ONE)
    pendingTrace.longRunningTrackedState = LongRunningTracesTracker.TO_TRACK
    addContinuation(newSpanOf(pendingTrace)).finish()

    then:
    then:
    1 * tracer.captureTraceConfig() >> traceConfig
    1 * bufferSpy.enqueue(_)
    _ * bufferSpy.longRunningSpansEnabled()
    0 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    _ * bufferSpy.longRunningSpansEnabled()
    0 * tracer.write({ it.size() == 1 })
    _ * tracer.getPartialFlushMinSpans() >> 10
    _ * traceConfig.getServiceMapping() >> [:]
    _ * tracer.getTimeWithNanoTicks(_)
    0 * _

    pendingTrace.isEnqueued == 0
  }

  def "continuation allows adding after root finished"() {
    setup:
    def latch = new CountDownLatch(1)

    def trace = factory.create(DDTraceId.ONE)
    def parent = addContinuation(newSpanOf(trace))
    TraceScope.Continuation continuation = continuations[0]

    expect:
    continuations.size() == 1

    when:
    parent.finish() // This should enqueue

    then:
    trace.size() == 1
    trace.pendingReferenceCount == 1
    !trace.rootSpanWritten
    _ * bufferSpy.longRunningSpansEnabled()
    1 * bufferSpy.enqueue(trace)
    _ * tracer.getPartialFlushMinSpans() >> 10
    1 * tracer.getTimeWithNanoTicks(_)
    0 * _

    when:
    def child = newSpanOf(parent)
    child.finish()

    then:
    trace.size() == 2
    trace.pendingReferenceCount == 1
    !trace.rootSpanWritten

    when:
    buffer.start()
    continuation.cancel()
    latch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount == 0
    trace.rootSpanWritten
    _ * bufferSpy.longRunningSpansEnabled()
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

    def trace = factory.create(DDTraceId.ONE)
    def parent = newSpanOf(trace)

    when:
    parent.finish() // This should enqueue
    parentLatch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount == 0
    trace.rootSpanWritten
    _ * bufferSpy.longRunningSpansEnabled()
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 }) >> {
      parentLatch.countDown()
    }
    _ * tracer.getPartialFlushMinSpans() >> 10
    1 * tracer.getTimeWithNanoTicks(_)
    0 * _

    when:
    def child = newSpanOf(parent)
    child.finish()
    childLatch.await()

    then:
    trace.size() == 0
    trace.pendingReferenceCount == 0
    trace.rootSpanWritten
    1 * bufferSpy.enqueue(trace)
    _ * bufferSpy.longRunningSpansEnabled()
    _ * tracer.getPartialFlushMinSpans() >> 10
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 }) >> {
      childLatch.countDown()
    }
    _ * traceConfig.getServiceMapping() >> [:]
    _ * tracer.getTimeWithNanoTicks(_)
    _ * bufferSpy.longRunningSpansEnabled()
    0 * _
  }

  def "flush clears the buffer"() {
    setup:
    buffer.start()
    def counter = new AtomicInteger(0)
    // Create a fake element that newer gets written
    def element = new PendingTraceBuffer.Element() {
        @Override
        long oldestFinishedTime() {
          return TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
        }

        @Override
        boolean lastReferencedNanosAgo(long nanos) {
          return false
        }

        @Override
        void write() {
          counter.incrementAndGet()
        }

        @Override
        DDSpan getRootSpan() {
          return null
        }

        @Override
        boolean setEnqueued(boolean enqueued) {
          return true
        }
        @Override
        boolean writeOnBufferFull() {
          return true
        }
      }

    when:
    buffer.enqueue(element)
    buffer.enqueue(element)
    buffer.enqueue(element)

    then:
    counter.get() == 0

    when:
    buffer.flush()

    then:
    counter.get() == 3
  }

  def "the same pending trace is not enqueued multiple times"() {
    setup:
    // Don't start the buffer thread

    when: "finish the root span"
    def pendingTrace = factory.create(DDTraceId.ONE)
    def span = newSpanOf(pendingTrace)
    span.finish()

    then:
    1 * tracer.captureTraceConfig() >> traceConfig
    pendingTrace.rootSpanWritten
    pendingTrace.isEnqueued == 0
    buffer.queue.size() == 0
    _ * bufferSpy.longRunningSpansEnabled()
    1 * tracer.writeTimer() >> Monitoring.DISABLED.newTimer("")
    1 * tracer.write({ it.size() == 1 })
    1 * tracer.getPartialFlushMinSpans() >> 10000
    1 * traceConfig.getServiceMapping() >> [:]
    2 * tracer.getTimeWithNanoTicks(_)
    0 * _

    when: "fail to fill the buffer"
    for (i in  1..buffer.queue.capacity()) {
      addContinuation(newSpanOf(span)).finish()
    }

    then:
    pendingTrace.isEnqueued == 1
    buffer.queue.size() == 1
    buffer.queue.capacity() * bufferSpy.enqueue(_)
    _ * bufferSpy.longRunningSpansEnabled()
    _ * tracer.getPartialFlushMinSpans() >> 10000
    _ * traceConfig.getServiceMapping() >> [:]
    _ * tracer.getTimeWithNanoTicks(_)
    0 * _

    when: "process the buffer"
    buffer.start()

    then:
    new PollingConditions(timeout: 3, initialDelay: 0, delay: 0.5, factor: 1).eventually {
      assert pendingTrace.isEnqueued == 0
    }
  }

  def addContinuation(DDSpan span) {
    def scope = scopeManager.activate(span, ScopeSource.INSTRUMENTATION, true)
    continuations << scope.capture()
    scope.close()
    return span
  }

  static DDSpan newSpanOf(PendingTrace trace) {
    return newSpanOf(trace, PrioritySampling.UNSET)
  }

  static DDSpan newSpanOf(PendingTrace trace, int samplingPriority) {
    def context = new DDSpanContext(
      trace.traceId,
      1,
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      samplingPriority,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      trace,
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty())
    return DDSpan.create("test", 0, context)
  }

  static DDSpan newSpanOf(DDSpan parent) {
    def trace = parent.context().trace
    def context = new DDSpanContext(
      trace.traceId,
      2,
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
      trace,
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty())
    return DDSpan.create("test", 0, context)
  }
}
