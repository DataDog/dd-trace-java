package datadog.trace.core

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.Config
import datadog.trace.api.DDTraceId
import datadog.trace.api.TraceConfig
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.time.SystemTimeSource
import datadog.trace.core.monitor.HealthMetrics
import spock.lang.Specification

class LongRunningTracesTrackerTest extends Specification {
  Config config = Mock(Config)
  int maxTrackedTraces = 10
  DDAgentFeaturesDiscovery features = Mock(DDAgentFeaturesDiscovery)
  LongRunningTracesTracker tracker = new LongRunningTracesTracker(config, maxTrackedTraces, features)
  def tracer = Mock(CoreTracer)
  def traceConfig = Mock(TraceConfig)
  PendingTraceBuffer buffer
  PendingTrace.Factory factory = null

  def setup() {
    features.supportsLongRunning() >> true
    tracer.captureTraceConfig() >> traceConfig
    traceConfig.getServiceMapping() >> [:]
    config.getLongRunningFlushInterval() >> 20
    config.longRunningTracesEnabled >> true
    buffer = PendingTraceBuffer.delaying(SystemTimeSource.INSTANCE, config, features)
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
    tracker.missedAdd == 1
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

  PendingTrace newTraceToTrack() {
    PendingTrace trace = factory.create(DDTraceId.ONE)
    PendingTraceBufferTest::newSpanOf(trace, PrioritySampling.SAMPLER_KEEP)
    return trace
  }
}
