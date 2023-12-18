package datadog.trace.core

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.trace.api.Config
import datadog.trace.api.DDTraceId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.time.SystemTimeSource
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.test.util.DDSpecification

class LongRunningTracesTrackerTest extends DDSpecification {
  Config config = Mock(Config)
  int maxTrackedTraces = 10
  def sharedCommunicationObjects = Mock(SharedCommunicationObjects)
  DDAgentFeaturesDiscovery features = new DDAgentFeaturesDiscovery(null, Monitoring.DISABLED, null, false, false)
  LongRunningTracesTracker tracker
  def tracer = Stub(CoreTracer)
  def traceConfig = Stub(CoreTracer.ConfigSnapshot)
  PendingTraceBuffer.DelayingPendingTraceBuffer buffer
  PendingTrace.Factory factory = null

  def setup() {
    features.supportsLongRunning = true
    tracer.captureTraceConfig() >> traceConfig
    traceConfig.getServiceMapping() >> [:]
    config.getLongRunningTraceFlushInterval() >> 20
    config.longRunningTraceEnabled >> true
    sharedCommunicationObjects.featuresDiscovery(_) >> features
    buffer = new PendingTraceBuffer.DelayingPendingTraceBuffer(maxTrackedTraces, SystemTimeSource.INSTANCE, config, sharedCommunicationObjects, HealthMetrics.NO_OP)
    tracker = buffer.runningTracesTracker
    factory = new PendingTrace.Factory(tracer, buffer, SystemTimeSource.INSTANCE, false, HealthMetrics.NO_OP)
  }

  def "null is not added"() {
    when:
    tracker.add(null)

    then:
    tracker.traceArray.size() == 0
  }

  def "trace with no span is not added"() {
    when:
    tracker.add(factory.create(DDTraceId.ONE))

    then:
    tracker.traceArray.size() == 0
  }

  def "trace without the right state are not tracked"() {
    given:
    def statesToTest = Arrays.asList(
      LongRunningTracesTracker.NOT_TRACKED,
      LongRunningTracesTracker.UNDEFINED,
      LongRunningTracesTracker.TRACKED,
      LongRunningTracesTracker.WRITE_RUNNING_SPANS,
      LongRunningTracesTracker.EXPIRED
      )
    when:
    statesToTest.each { stateToTest ->
      def trace = newTraceToTrack()
      trace.longRunningTrackedState = stateToTest
      tracker.add(trace)
    }
    then:
    tracker.traceArray.size() == 0

    when:
    tracker.add(newTraceToTrack())
    then:
    tracker.traceArray.size() == 1
  }


  def "maxTrackedTraces is enforced"() {
    given:
    (1..maxTrackedTraces).each {
      tracker.add(newTraceToTrack())
    }

    when:
    tracker.add(newTraceToTrack())

    then:
    tracker.traceArray.size() == maxTrackedTraces
    tracker.dropped == 1
  }

  def "expired traces"() {
    given:
    def trace = newTraceToTrack()
    tracker.add(trace)

    when:
    tracker.flushAndCompact(tracker.maxTrackedDurationMilli - 1000)

    then:
    tracker.traceArray.size() == 1
    trace.longRunningTrackedState == LongRunningTracesTracker.WRITE_RUNNING_SPANS

    when:
    tracker.flushAndCompact(1 + tracker.maxTrackedDurationMilli)

    then:
    tracker.traceArray.size() == 0

    trace.longRunningTrackedState == LongRunningTracesTracker.EXPIRED
  }

  def "agent disabled feature"() {
    given:
    def trace = newTraceToTrack()
    tracker.add(trace)
    features.supportsLongRunning = false

    when:
    tracker.flushAndCompact(tracker.flushPeriodMilli - 1000)

    then:
    tracker.traceArray.size() == 0
  }

  PendingTrace newTraceToTrack() {
    PendingTrace trace = factory.create(DDTraceId.ONE)
    PendingTraceBufferTest::newSpanOf(trace, PrioritySampling.SAMPLER_KEEP)
    return trace
  }

  def "priority evaluation: #priority"() {
    given:
    def trace = newTraceToTrack()
    def span = trace.spans.peek()
    span.context().samplingPriority = priority
    tracker.add(trace)

    when:
    tracker.flushAndCompact(tracker.maxTrackedDurationMilli - 1000)

    then:
    tracker.traceArray.size() == trackerExpectedSize
    trace.longRunningTrackedState == traceExpectedState

    where:
    priority | trackerExpectedSize | traceExpectedState
    PrioritySampling.SAMPLER_DROP | 0 | LongRunningTracesTracker.NOT_TRACKED
    PrioritySampling.USER_DROP | 0 | LongRunningTracesTracker.NOT_TRACKED
    PrioritySampling.USER_KEEP | 1 | LongRunningTracesTracker.WRITE_RUNNING_SPANS
    PrioritySampling.SAMPLER_KEEP | 1 | LongRunningTracesTracker.WRITE_RUNNING_SPANS
  }
}
