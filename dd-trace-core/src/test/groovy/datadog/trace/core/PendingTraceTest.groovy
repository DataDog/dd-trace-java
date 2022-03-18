package datadog.trace.core

import spock.lang.Timeout

import java.util.concurrent.TimeUnit

class PendingTraceTest extends PendingTraceTestBase {

  @Override
  protected boolean useStrictTraceWrites() {
    // This tests the behavior of the relaxed pending trace implementation
    return false
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
    trace.pendingReferenceCount == 1
    trace.finishedSpans.asList() == [rootSpan]
    writer == []

    when: "root span buffer delay expires"
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount == 1
    trace.finishedSpans.isEmpty()
    writer == [[rootSpan]]
    writer.traceCount.get() == 1
  }

  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  def "traces keep alive are sent for long running spans"() {
    given:
    def keepalive = new TraceKeepAlive(1)
    when: "Open a long running spans"
    def scope = tracer.activateSpan(rootSpan)
    keepalive.onPendingTraceBegins(trace)

    then:
    trace.pendingReferenceCount == 1
    trace.finishedSpans.empty
    trace.unfinishedSpans.asList() == [rootSpan]
    keepalive.pendingTraces.size() == 1
    keepalive.pendingTraces.containsKey(trace)
    writer == []

    when: "keepAlive tasks sweeps #1"
    sleep(10)
    keepalive.run(null)

    then:
    trace.pendingReferenceCount == 1
    trace.finishedSpans.size() == 0
    trace.unfinishedSpans.asList() == [rootSpan]
    writer.traceCount.get() == 1
    writer.firstTrace().size() == 1
    writer.firstTrace().first().partialVersion > 0

    when: "keepAlive tasks sweeps #2"
    sleep(10)
    keepalive.run(null)

    then:
    trace.pendingReferenceCount == 1
    trace.finishedSpans.size() == 0
    trace.unfinishedSpans.asList() == [rootSpan]
    writer.traceCount.get() == 2
    writer[0].size() == 1
    writer[1].size() == 1
    writer[0].first().partialVersion < writer[1].first().partialVersion

    when: "open a child on a long running span"
    def child = tracer.startSpan("child", rootSpan.context(),false)
    sleep(10)
    child.finish()
    keepalive.run(null)

    then:
    trace.pendingReferenceCount == 1
    trace.finishedSpans.size() == 0 // should already have been flushed at this point
    trace.unfinishedSpans.asList() == [rootSpan]
    writer.traceCount.get() == 3
    writer[2].size() == 2
    writer[2][1] == child
    writer[2][0].partialVersion > writer[1][0].partialVersion

    when: "close pending long running spans"
    rootSpan.finish()

    then:
    trace.pendingReferenceCount == 0
    trace.finishedSpans.size() == 0 //already flushed to the writer at this point also
    trace.unfinishedSpans.size() == 0
    writer.traceCount.get() == 4
    writer[3].size() == 1
    writer[3][0] == rootSpan
    writer[3][0].partialVersion == null

    when: "nothing more to keep alive"
    keepalive.run(null)

    then:
    trace.finishedSpans.size() == 0
    trace.unfinishedSpans.size() == 0
    writer.traceCount.get() == 4
  }
}
