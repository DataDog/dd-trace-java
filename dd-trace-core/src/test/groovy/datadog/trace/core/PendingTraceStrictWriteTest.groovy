package datadog.trace.core

class PendingTraceStrictWriteTest extends PendingTraceTestBase {

  def "trace is not reported until unfinished continuation is closed"() {
    when:
    def scope = tracer.activateSpan(rootSpan)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()
    scope.close()
    rootSpan.finish()

    then:
    trace.pendingReferenceCount == 1
    trace.spans.asList() == [rootSpan]
    writer == []

    when: "root span buffer delay expires"
    writer.waitForTracesMax(1, 1)

    then:
    trace.pendingReferenceCount == 1
    trace.spans.asList() == [rootSpan]
    writer == []
    writer.traceCount.get() == 0

    when: "continuation is closed"
    continuation.cancel()

    then:
    trace.pendingReferenceCount == 0
    trace.spans.isEmpty()
    writer == [[rootSpan]]
    writer.traceCount.get() == 1
  }

  def "negative reference count throws an exception"() {
    when:
    def scope = tracer.activateSpan(rootSpan)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()
    scope.close()
    rootSpan.finish()

    then:
    trace.pendingReferenceCount == 1
    trace.spans.asList() == [rootSpan]
    writer == []

    when: "continuation is finished the first time"
    continuation.cancel()

    then:
    trace.pendingReferenceCount == 0
    trace.spans.isEmpty()
    writer == [[rootSpan]]
    writer.traceCount.get() == 1

    when: "continuation is finished the second time"
    // Yes this should be guarded by the used flag in the continuation,
    // so cancel it anyway to trigger the exception
    trace.cancelContinuation(continuation)

    then:
    thrown IllegalStateException
  }
}
