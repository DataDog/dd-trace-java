package datadog.trace.core

import datadog.trace.api.DDTraceId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.time.TimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.propagation.PropagationTags
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

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
      AgentTracer.NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty()))
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
    trace.spans.asList() == [rootSpan]
    writer == []

    when: "root span buffer delay expires"
    writer.waitForTraces(1)

    then:
    trace.pendingReferenceCount == 1
    trace.spans.isEmpty()
    writer == [[rootSpan]]
    writer.traceCount.get() == 1
  }
  def "verify healthmetrics called"() {
    setup:
    def tracer = Mock(CoreTracer)
    def traceConfig = Mock(CoreTracer.ConfigSnapshot)
    def buffer = Mock(PendingTraceBuffer)
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
    def tracer = Mock(CoreTracer)
    def traceConfig = Mock(CoreTracer.ConfigSnapshot)
    def buffer = Mock(PendingTraceBuffer)
    def healthMetrics = Mock(HealthMetrics)
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
    def tracer = Mock(CoreTracer)
    def traceConfig = Mock(CoreTracer.ConfigSnapshot)
    def buffer = Mock(PendingTraceBuffer)
    def healthMetrics = Mock(HealthMetrics)
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
