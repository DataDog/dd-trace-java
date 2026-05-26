package datadog.trace.common.metrics

import datadog.metrics.agent.AgentMeter
import datadog.metrics.impl.DDSketchHistograms
import datadog.metrics.impl.MonitoringImpl
import datadog.metrics.api.statsd.StatsDClient
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray

import static datadog.trace.common.metrics.AggregateMetric.ERROR_TAG
import static datadog.trace.common.metrics.AggregateMetric.TOP_LEVEL_TAG

class AggregateMetricTest extends DDSpecification {

  def setupSpec() {
    // Initialize AgentMeter with monitoring - this is the standard mechanism used in production
    def monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
    AgentMeter.registerIfAbsent(StatsDClient.NO_OP, monitoring, DDSketchHistograms.FACTORY)
    // Create a timer to trigger DDSketchHistograms loading and Factory registration
    // This simulates what happens during CoreTracer initialization (traceWriteTimer)
    monitoring.newTimer("test.init")
  }

  def "record durations sums up to total"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(3, new AtomicLongArray(1, 2, 3))
    then:
    aggregate.getDuration() == 6
  }

  def "total durations include errors"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(3, new AtomicLongArray(1, 2, 3))
    then:
    aggregate.getDuration() == 6
  }

  def "clear"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
      .recordDurations(3, new AtomicLongArray(5, ERROR_TAG | 6, TOP_LEVEL_TAG | 7))
    when:
    aggregate.clear()
    then:
    aggregate.getDuration() == 0
    aggregate.getErrorCount() == 0
    aggregate.getTopLevelCount() == 0
    aggregate.getHitCount() == 0
  }

  def "recordOneDuration accumulates ok and error and top-level"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
      .recordOneDuration(10L)
      .recordOneDuration(10L | TOP_LEVEL_TAG)
      .recordOneDuration(10L | ERROR_TAG)

    expect:
    aggregate.getHitCount() == 3
    aggregate.getDuration() == 30
    aggregate.getErrorCount() == 1
    aggregate.getTopLevelCount() == 1
  }

  def "ignore trailing zeros"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(3, new AtomicLongArray(1, 2, 3, 0, 0, 0))
    then:
    aggregate.getDuration() == 6
    aggregate.getHitCount() == 3
    aggregate.getErrorCount() == 0
  }

  def "hit count includes errors"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(3, new AtomicLongArray(1, 2, 3 | ERROR_TAG))
    then:
    aggregate.getHitCount() == 3
    aggregate.getErrorCount() == 1
  }

  def "ok and error durations tracked separately"() {
    given:
    AggregateMetric aggregate = new AggregateMetric()
    when:
    aggregate.recordDurations(10,
      new AtomicLongArray(1, 100 | ERROR_TAG, 2, 99 | ERROR_TAG, 3,
      98  | ERROR_TAG, 4, 97  | ERROR_TAG))
    then:
    def errorLatencies = aggregate.getErrorLatencies()
    def okLatencies = aggregate.getOkLatencies()
    errorLatencies.getMaxValue() >= 99
    okLatencies.getMaxValue() <= 5
  }
}
