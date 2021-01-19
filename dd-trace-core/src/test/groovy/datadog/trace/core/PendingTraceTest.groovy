package datadog.trace.core

import spock.lang.Timeout

import java.util.concurrent.TimeUnit

class PendingTraceTest extends PendingTraceTestBase {

  @Override
  CoreTracer.CoreTracerBuilder getBuilder() {
    return CoreTracer.builder()
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
}
