package datadog.trace.core

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.api.Config
import datadog.trace.api.DDTraceId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.TimeUnit

class LongRunningTracesTrackerTest extends DDSpecification {
  Config config = Mock(Config)
  int maxTrackedTraces = 10
  def sharedCommunicationObjects = Mock(SharedCommunicationObjects)
  DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
  LongRunningTracesTracker tracker
  def tracer = Mock(CoreTracer)
  def traceConfig = Stub(CoreTracer.ConfigSnapshot)
  PendingTraceBuffer.DelayingPendingTraceBuffer buffer
  PendingTrace.Factory factory = null
  def timeSource = new ControllableTimeSource()

  def setup() {
    timeSource.set(0L)
    features.supportsLongRunning() >> true
    tracer.captureTraceConfig() >> traceConfig
    tracer.getTimeWithNanoTicks(_) >> { Long x -> x }
    traceConfig.getServiceMapping() >> [:]
    config.getLongRunningTraceInitialFlushInterval() >> 10
    config.getLongRunningTraceFlushInterval() >> 20
    config.longRunningTraceEnabled >> true
    sharedCommunicationObjects.featuresDiscovery(_) >> features
    buffer = new PendingTraceBuffer.DelayingPendingTraceBuffer(maxTrackedTraces, timeSource, config, sharedCommunicationObjects, HealthMetrics.NO_OP)
    tracker = buffer.runningTracesTracker
    factory = new PendingTrace.Factory(tracer, buffer, timeSource, false, HealthMetrics.NO_OP)
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

  def "trace remains tracked but not written when agent long running feature not available"() {
    given:
    def trace = newTraceToTrack()
    tracker.add(trace)

    when:
    tracker.flushAndCompact(tracker.flushPeriodMilli - 1000)

    then:
    1 * features.supportsLongRunning() >> false
    tracker.traceArray.size() == 1
    tracker.traceArray[0].longRunningTrackedState == LongRunningTracesTracker.TRACKED
    tracker.traceArray[0].getLastWriteTime() == 0
  }

  def flushAt(long timeMilli) {
    timeSource.set(TimeUnit.MILLISECONDS.toNanos(timeMilli))
    tracker.flushAndCompact(timeMilli)
  }

  def "flush logic with initial flush"() {
    given:
    def trace = newTraceToTrack()
    tracker.add(trace)

    when: // Before the initial flush
    flushAt(tracker.initialFlushPeriodMilli - 1000)

    then:
    0 * tracer.write(_)

    when: // After the initial flush
    flushAt(tracker.initialFlushPeriodMilli + 1000)

    then:
    1 * tracer.write(_)
    trace.getLastWriteTime() == TimeUnit.MILLISECONDS.toNanos(tracker.initialFlushPeriodMilli + 1000)

    when: // Before the regular flush
    flushAt(tracker.initialFlushPeriodMilli + tracker.flushPeriodMilli - 1000)

    then:
    0 * tracer.write(_)
    trace.getLastWriteTime() == TimeUnit.MILLISECONDS.toNanos(tracker.initialFlushPeriodMilli + 1000)

    when: // After the first regular flush
    flushAt(tracker.initialFlushPeriodMilli + tracker.flushPeriodMilli + 2000)

    then:
    1 * tracer.write(_)
    trace.getLastWriteTime() == TimeUnit.MILLISECONDS.toNanos(tracker.initialFlushPeriodMilli + tracker.flushPeriodMilli + 2000)
  }

  PendingTrace newTraceToTrack() {
    PendingTrace trace = factory.create(DDTraceId.ONE)
    PendingTraceBufferTest::newSpanOf(trace, PrioritySampling.SAMPLER_KEEP, 0)
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

  def "getTracesAsJson with no traces"() {
    when:
    def json = tracker.getTracesAsJson()

    then:
    json == ""
  }

  def "getTracesAsJson with traces"() {
    given:
    def trace = newTraceToTrack()
    tracker.add(trace)

    when:
    def json = tracker.getTracesAsJson()

    then:
    json != null
    !json.isEmpty()
    json.contains('"service"')
    json.contains('"name"')
  }

  def "testing tracer flare dump with trace"() {
    given:
    def trace = newTraceToTrack()
    tracker.add(trace)

    when:
    def entries = PendingTraceBufferTest.buildAndExtractZip()

    then:
    entries.containsKey("long_running_traces.txt")

    def jsonContent = entries["long_running_traces.txt"] as String
    jsonContent.contains('"service"')
    jsonContent.contains('"name"')
  }
}
