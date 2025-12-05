package datadog.trace.core

import datadog.environment.JavaVirtualMachine
import datadog.trace.api.DDTraceId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.time.TimeSource
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.propagation.PropagationTags
import spock.lang.IgnoreIf
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

@IgnoreIf(reason = """
Oracle JDK 1.8 did not merge the fix in JDK-8058322, leading to the JVM failing to correctly 
extract method parameters without args, when the code is compiled on a later JDK (targeting 8). 
This can manifest when creating mocks.
""", value = {
  JavaVirtualMachine.isOracleJDK8()
})
class PendingTraceTest extends PendingTraceTestBase {

  @Override
  protected boolean useStrictTraceWrites() {
    // This tests the behavior of the relaxed pending trace implementation
    return false
  }
  protected DDSpan createSimpleSpan(PendingTrace trace){
    return createSimpleSpanWithID(trace,1)
  }

  protected DDSpan createSimpleSpanWithID(PendingTrace trace, long id){
    return new DDSpan("test", 0L, new DDSpanContext(
      DDTraceId.from(1),
      id,
      0,
      null,
      "",
      "",
      "",
      PrioritySampling.UNSET,
      "",
      [:],
      false,
      "",
      0,
      trace,
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty()),
      null)
  }

  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  def "trace is still reported when unfinished continuation discarded"() {
    when:
    def scope = tracer.activateSpan(rootSpan)
    tracer.captureActiveSpan()
    scope.close()
    rootSpan.finish()

    then:
    traceCollector.pendingReferenceCount == 1
    traceCollector.spans.asList() == [rootSpan]
    writer == []

    when: "root span buffer delay expires"
    writer.waitForTraces(1)

    then:
    traceCollector.pendingReferenceCount == 1
    traceCollector.spans.isEmpty()
    writer == [[rootSpan]]
    writer.traceCount.get() == 1
  }
  def "verify healthmetrics called"() {
    setup:
    def tracer = Stub(CoreTracer)
    def traceConfig = Stub(CoreTracer.ConfigSnapshot)
    def buffer = Stub(PendingTraceBuffer)
    def healthMetrics = Mock(HealthMetrics)
    tracer.captureTraceConfig() >> traceConfig
    traceConfig.getServiceMapping() >> [:]
    PendingTrace trace = new PendingTrace(tracer, DDTraceId.from(0), buffer, Mock(TimeSource), null, false, healthMetrics)

    when:
    rootSpan = createSimpleSpan(trace)
    trace.registerSpan(rootSpan)

    then:
    1 * healthMetrics.onCreateSpan()

    when:
    rootSpan.finish()

    then:
    1 * healthMetrics.onCreateTrace()
  }

  def "write when writeRunningSpans is disabled: only completed spans are written"() {
    setup:
    def tracer = Stub(CoreTracer)
    def traceConfig = Stub(CoreTracer.ConfigSnapshot)
    def buffer = Stub(PendingTraceBuffer)
    def healthMetrics = Stub(HealthMetrics)
    tracer.captureTraceConfig() >> traceConfig
    traceConfig.getServiceMapping() >> [:]
    PendingTrace trace = new PendingTrace(tracer, DDTraceId.from(0), buffer, Mock(TimeSource), null, false, healthMetrics)
    buffer.longRunningSpansEnabled() >> true

    def span1 = createSimpleSpanWithID(trace,39)
    span1.durationNano = 31
    span1.samplingPriority = PrioritySampling.USER_KEEP
    trace.registerSpan(span1)

    def unfinishedSpan = createSimpleSpanWithID(trace, 191)
    trace.registerSpan(unfinishedSpan)

    def span2 = createSimpleSpanWithID(trace, 9999)
    span2.durationNano = 9191
    trace.registerSpan(span2)
    def traceToWrite = new ArrayList<>(0)

    when:
    def completedSpans = trace.enqueueSpansToWrite(traceToWrite, false)

    then:
    completedSpans == 2
    traceToWrite.size() == 2
    traceToWrite.containsAll([span1, span2])
    trace.spans.size() == 1
    trace.spans.pop() == unfinishedSpan
  }

  def "write when writeRunningSpans is enabled: complete and running spans are written"() {
    setup:
    def tracer = Stub(CoreTracer)
    def traceConfig = Stub(CoreTracer.ConfigSnapshot)
    def buffer = Stub(PendingTraceBuffer)
    def healthMetrics = Stub(HealthMetrics)
    tracer.captureTraceConfig() >> traceConfig
    traceConfig.getServiceMapping() >> [:]
    PendingTrace trace = new PendingTrace(tracer, DDTraceId.from(0), buffer, Mock(TimeSource), null, false, healthMetrics)
    buffer.longRunningSpansEnabled() >> true

    def span1 = createSimpleSpanWithID(trace,39)
    span1.durationNano = 31
    span1.samplingPriority = PrioritySampling.USER_KEEP
    trace.registerSpan(span1)

    def unfinishedSpan = createSimpleSpanWithID(trace, 191)
    trace.registerSpan(unfinishedSpan)

    def span2 = createSimpleSpanWithID(trace, 9999)
    span2.setServiceName("9191")
    span2.durationNano = 9191
    trace.registerSpan(span2)

    def unfinishedSpan2 = createSimpleSpanWithID(trace, 77771)
    trace.registerSpan(unfinishedSpan2)

    def traceToWrite = new ArrayList<>(0)

    when:
    def completedSpans = trace.enqueueSpansToWrite(traceToWrite, true)

    then:
    completedSpans == 2
    traceToWrite.size() == 4
    traceToWrite.containsAll([span1, span2, unfinishedSpan, unfinishedSpan2])
    trace.spans.size() == 2
    trace.spans.containsAll([unfinishedSpan, unfinishedSpan2])
  }
}
