package datadog.trace.core

class PendingTraceStrictWriteTest extends PendingTraceTestBase {

  def "trace is not reported until unfinished continuation is closed"() {
    when:
    def scope = tracer.activateSpan(rootSpan)
    def continuation = tracer.captureActiveSpan()
    scope.close()
    rootSpan.finish()

    then:
    traceCollector.pendingReferenceCount == 1
    traceCollector.spans.asList() == [rootSpan]
    writer == []

    when: "root span buffer delay expires"
    writer.waitForTracesMax(1, 1)

    then:
    traceCollector.pendingReferenceCount == 1
    traceCollector.spans.asList() == [rootSpan]
    writer == []
    writer.traceCount.get() == 0

    when: "continuation is closed"
    continuation.cancel()

    then:
    traceCollector.pendingReferenceCount == 0
    traceCollector.spans.isEmpty()
    writer == [[rootSpan]]
    writer.traceCount.get() == 1
  }

  def "negative reference count throws an exception"() {
    when:
    def scope = tracer.activateSpan(rootSpan)
    def continuation = tracer.captureActiveSpan()
    scope.close()
    rootSpan.finish()

    then:
    traceCollector.pendingReferenceCount == 1
    traceCollector.spans.asList() == [rootSpan]
    writer == []

    when: "continuation is finished the first time"
    continuation.cancel()

    then:
    traceCollector.pendingReferenceCount == 0
    traceCollector.spans.isEmpty()
    writer == [[rootSpan]]
    writer.traceCount.get() == 1

    when: "continuation is finished the second time"
    // Yes this should be guarded by the used flag in the continuation,
    // so remove it anyway to trigger the exception
    traceCollector.removeContinuation(continuation)

    then:
    thrown IllegalStateException
  }
}
