package datadog.trace.civisibility.telemetry

import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricData
import datadog.trace.api.civisibility.telemetry.TagValue
import datadog.trace.api.civisibility.telemetry.tag.Endpoint
import datadog.trace.api.civisibility.telemetry.tag.EventType
import datadog.trace.api.civisibility.telemetry.tag.Library
import datadog.trace.api.telemetry.MetricCollector
import spock.lang.Specification

class CiVisibilityMetricCollectorTest extends Specification {

  def "test distribution metric submission"() {
    setup:
    def collector = new CiVisibilityMetricCollectorImpl()

    when:
    collector.add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES, 123, Endpoint.CODE_COVERAGE)
    collector.prepareMetrics()
    def metrics = collector.drainDistributionSeries()

    then:
    metrics == [
      new MetricCollector.DistributionSeriesPoint(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES.getName(), true, "civisibility", 123, [Endpoint.CODE_COVERAGE.asString()])
    ]
  }

  def "test distribution metrics are not aggregated"() {
    setup:
    def collector = new CiVisibilityMetricCollectorImpl()

    when:
    collector.add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES, 123, Endpoint.CODE_COVERAGE)
    collector.add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES, 456, Endpoint.CODE_COVERAGE)
    collector.prepareMetrics()
    def metrics = collector.drainDistributionSeries()

    then:
    metrics == [
      new MetricCollector.DistributionSeriesPoint(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES.getName(), true, "civisibility", 123, [Endpoint.CODE_COVERAGE.asString()]),
      new MetricCollector.DistributionSeriesPoint(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES.getName(), true, "civisibility", 456, [Endpoint.CODE_COVERAGE.asString()])
    ]
  }

  def "test count metric submission"() {
    setup:
    def collector = new CiVisibilityMetricCollectorImpl()

    when:
    collector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 123)
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics == [new CiVisibilityMetricData(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS.getName(), 123)]
  }

  def "test count metric aggregation"() {
    setup:
    def collector = new CiVisibilityMetricCollectorImpl()

    when:
    collector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 123)
    collector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 123)
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics == [new CiVisibilityMetricData(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS.getName(), 246)]
  }

  def "test count metrics submitted in different cycles are not aggregated"() {
    setup:
    def collector = new CiVisibilityMetricCollectorImpl()

    when:
    collector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 123)
    collector.prepareMetrics()

    collector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 123)
    collector.prepareMetrics()

    def metrics = collector.drain()

    then:
    metrics == [
      new CiVisibilityMetricData(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS.getName(), 246),
      new CiVisibilityMetricData(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS.getName(), 246)
    ]
  }

  def "test count metrics with different tags are not aggregated"() {
    setup:
    def collector = new CiVisibilityMetricCollectorImpl()

    when:
    collector.add(CiVisibilityCountMetric.ITR_FORCED_RUN, 123, EventType.MODULE)
    collector.add(CiVisibilityCountMetric.ITR_FORCED_RUN, 456, EventType.SESSION)
    collector.prepareMetrics()

    def metrics = collector.drain()

    then:
    metrics == [
      new CiVisibilityMetricData(CiVisibilityCountMetric.ITR_FORCED_RUN.getName(), 123, EventType.MODULE),
      new CiVisibilityMetricData(CiVisibilityCountMetric.ITR_FORCED_RUN.getName(), 456, EventType.SESSION)
    ]
  }

  def "test different count metrics are not aggregated"() {
    setup:
    def collector = new CiVisibilityMetricCollectorImpl()

    when:
    collector.add(CiVisibilityCountMetric.ITR_FORCED_RUN, 123, EventType.MODULE)
    collector.add(CiVisibilityCountMetric.ITR_SKIPPED, 456, EventType.MODULE)
    collector.prepareMetrics()

    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics.contains(new CiVisibilityMetricData(CiVisibilityCountMetric.ITR_FORCED_RUN.getName(), 123, EventType.MODULE))
    metrics.contains(new CiVisibilityMetricData(CiVisibilityCountMetric.ITR_SKIPPED.getName(), 456, EventType.MODULE))
  }

  def "test exception is thrown when a distribution metric is tagged with a tag that is not allowed for it"() {
    setup:
    def collector = new CiVisibilityMetricCollectorImpl()

    when:
    collector.add(CiVisibilityDistributionMetric.GIT_COMMAND_MS, 123, Library.JACOCO)

    then:
    thrown IllegalArgumentException
  }

  def "test exception is thrown when a count metric is tagged with a tag that is not allowed for it"() {
    setup:
    def collector = new CiVisibilityMetricCollectorImpl()

    when:
    collector.add(CiVisibilityCountMetric.ITR_FORCED_RUN, 123, Library.JACOCO)

    then:
    thrown IllegalArgumentException
  }

  /**
   * This test enumerates a few different tag combinations for every metric,
   * then submits a metric count for each one.
   * The goal is to ensure that index calculation logic and card-marking are done right.
   */
  def "test submission of different count metric pairs"() {
    setup:
    List<PossibleMetric> possibleMetrics = []

    for (CiVisibilityCountMetric metric : CiVisibilityCountMetric.values()) {
      def metricTags = metric.getTags()

      int cartesianProductSizeLimit = 20 // limiting the number of combinations to avoid OOM/timeout
      for (TagValue[] tags : cartesianProduct(metricTags, cartesianProductSizeLimit)) { // iterate over combinations of metric tags
        possibleMetrics += new PossibleMetric(metric, tags)
      }
    }

    def collector = new CiVisibilityMetricCollectorImpl()

    expect:
    for (int i = 0; i < possibleMetrics.size() - 1; i++) {
      def firstMetric = possibleMetrics.get(i)
      for (int j = i + 1; j < possibleMetrics.size(); j++) {
        def secondMetric = possibleMetrics.get(j)

        // deriving metric values from indices (as indices are unique, it's convenient to check that every metric has the correct value when drained)
        // +1 is needed because 0 cannot be used as a metric value - metrics with value 0 are not submitted
        int firstMetricValue = i + 1
        int secondMetricValue = j + 1

        collector.add(firstMetric.metric, firstMetricValue, firstMetric.tags)
        collector.add(secondMetric.metric, secondMetricValue, secondMetric.tags)
        collector.prepareMetrics()

        def metrics = collector.drain()
        assert metrics.size() == 2
        assert metrics.contains(new CiVisibilityMetricData(firstMetric.metric.getName(), firstMetricValue, firstMetric.tags))
        assert metrics.contains(new CiVisibilityMetricData(secondMetric.metric.getName(), secondMetricValue, secondMetric.tags))
      }
    }
  }

  private Collection<TagValue[]> cartesianProduct(Class<? extends TagValue>[] sets, int sizeLimit) {
    Collection<TagValue[]> tuples = new ArrayList<>()
    cartesianProductBacktrack(sets, tuples, new ArrayDeque<>(), 0, sizeLimit)
    return tuples
  }

  private void cartesianProductBacktrack(Class<? extends TagValue>[] sets, Collection<TagValue[]> tuples, Deque<TagValue> currentTuple, int offset, int sizeLimit) {
    if (tuples.size() >= sizeLimit) {
      return
    }

    if (offset == sets.length) {
      int idx = 0
      TagValue[] tuple = new TagValue[currentTuple.size()]
      for (TagValue element : currentTuple) {
        tuple[tuple.length - ++idx] = element
      }
      tuples.add(tuple)
      return
    }

    // a branch where we omit current tag
    cartesianProductBacktrack(sets, tuples, currentTuple, offset + 1, sizeLimit)

    for (TagValue element : sets[offset].getEnumConstants()) {
      currentTuple.push(element)
      cartesianProductBacktrack(sets, tuples, currentTuple, offset + 1, sizeLimit)
      currentTuple.pop()
    }
  }

  private static final class PossibleMetric {
    private final CiVisibilityCountMetric metric
    private final TagValue[] tags

    PossibleMetric(CiVisibilityCountMetric metric, TagValue[] tags) {
      this.metric = metric
      this.tags = tags
    }
  }

}
