package datadog.trace.core

import datadog.trace.api.DDTraceId
import datadog.trace.api.TraceConfig
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
    return new DDSpan((long)0,new DDSpanContext(
      DDTraceId.from(1),
      1,
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
  def "verify healthmetrics called"() {
    setup:
    def tracer = Mock(CoreTracer)
    def traceConfig = Mock(TraceConfig)
    def buffer = Mock(PendingTraceBuffer)
    def healthMetrics = Mock(HealthMetrics)
    tracer.captureTraceConfig() >> traceConfig
    traceConfig.getServiceMapping() >> [:]
    PendingTrace trace = new PendingTrace(tracer,DDTraceId.from(0),buffer,Mock(TimeSource),false,healthMetrics)
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
}
