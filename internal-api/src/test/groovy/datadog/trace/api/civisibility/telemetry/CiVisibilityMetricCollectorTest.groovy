package datadog.trace.api.civisibility.telemetry

import datadog.trace.api.civisibility.telemetry.tag.Endpoint
import datadog.trace.api.civisibility.telemetry.tag.EventType
import datadog.trace.api.civisibility.telemetry.tag.Library
import spock.lang.Specification

class CiVisibilityMetricCollectorTest extends Specification {

  def "test distribution metric submission"() {
    setup:
    def collector = new CiVisibilityMetricCollector()

    when:
    collector.add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES, 123, Endpoint.CODE_COVERAGE)
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics == [
      new CiVisibilityMetricData(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES.getName(), CiVisibilityMetricData.Type.DISTRIBUTION, 123, Endpoint.CODE_COVERAGE)
    ]
  }

  def "test distribution metrics are not aggregated"() {
    setup:
    def collector = new CiVisibilityMetricCollector()

    when:
    collector.add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES, 123, Endpoint.CODE_COVERAGE)
    collector.add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES, 456, Endpoint.CODE_COVERAGE)
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics == [
      new CiVisibilityMetricData(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES.getName(), CiVisibilityMetricData.Type.DISTRIBUTION, 123, Endpoint.CODE_COVERAGE),
      new CiVisibilityMetricData(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES.getName(), CiVisibilityMetricData.Type.DISTRIBUTION, 456, Endpoint.CODE_COVERAGE)
    ]
  }

  def "test count metric submission"() {
    setup:
    def collector = new CiVisibilityMetricCollector()

    when:
    collector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 123)
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics == [
      new CiVisibilityMetricData(
      CiVisibilityCountMetric.CODE_COVERAGE_ERRORS.getName(),
      CiVisibilityMetricData.Type.COUNT,
      123)
    ]
  }

  def "test count metric aggregation"() {
    setup:
    def collector = new CiVisibilityMetricCollector()

    when:
    collector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 123)
    collector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 123)
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics == [
      new CiVisibilityMetricData(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS.getName(), CiVisibilityMetricData.Type.COUNT, 246)
    ]
  }

  def "test count metrics submitted in different cycles are not aggregated"() {
    setup:
    def collector = new CiVisibilityMetricCollector()

    when:
    collector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 123)
    collector.prepareMetrics()

    collector.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 123)
    collector.prepareMetrics()

    def metrics = collector.drain()

    then:
    metrics == [
      new CiVisibilityMetricData(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS.getName(), CiVisibilityMetricData.Type.COUNT, 246),
      new CiVisibilityMetricData(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS.getName(), CiVisibilityMetricData.Type.COUNT, 246)
    ]
  }

  def "test count metrics with different tags are not aggregated"() {
    setup:
    def collector = new CiVisibilityMetricCollector()

    when:
    collector.add(CiVisibilityCountMetric.ITR_FORCED_RUN, 123, EventType.MODULE)
    collector.add(CiVisibilityCountMetric.ITR_FORCED_RUN, 456, EventType.SESSION)
    collector.prepareMetrics()

    def metrics = collector.drain()

    then:
    metrics == [
      new CiVisibilityMetricData(CiVisibilityCountMetric.ITR_FORCED_RUN.getName(), CiVisibilityMetricData.Type.COUNT, 123, EventType.MODULE),
      new CiVisibilityMetricData(CiVisibilityCountMetric.ITR_FORCED_RUN.getName(), CiVisibilityMetricData.Type.COUNT, 456, EventType.SESSION)
    ]
  }

  def "test different count metrics are not aggregated"() {
    setup:
    def collector = new CiVisibilityMetricCollector()

    when:
    collector.add(CiVisibilityCountMetric.ITR_FORCED_RUN, 123, EventType.MODULE)
    collector.add(CiVisibilityCountMetric.ITR_SKIPPED, 456, EventType.MODULE)
    collector.prepareMetrics()

    def metrics = collector.drain()

    then:
    metrics.size() == 2
    metrics.contains(new CiVisibilityMetricData(CiVisibilityCountMetric.ITR_FORCED_RUN.getName(), CiVisibilityMetricData.Type.COUNT, 123, EventType.MODULE))
    metrics.contains(new CiVisibilityMetricData(CiVisibilityCountMetric.ITR_SKIPPED.getName(), CiVisibilityMetricData.Type.COUNT, 456, EventType.MODULE))
  }

  def "test exception is thrown when a distribution metric is tagged with a tag that is not allowed for it"() {
    setup:
    def collector = new CiVisibilityMetricCollector()

    when:
    collector.add(CiVisibilityDistributionMetric.GIT_COMMAND_MS, 123, Library.JACOCO)

    then:
    thrown IllegalArgumentException
  }

  def "test exception is thrown when a count metric is tagged with a tag that is not allowed for it"() {
    setup:
    def collector = new CiVisibilityMetricCollector()

    when:
    collector.add(CiVisibilityCountMetric.ITR_FORCED_RUN, 123, Library.JACOCO)

    then:
    thrown IllegalArgumentException
  }

  /**
   * This test enumerates all possible metric+tags variants,
   * then tries submitting all possible variant pairs (combinations of 2 different metric+tags).
   * The goal is to ensure that index calculation logic and card-marking are done right.
   */
  def "test submission of all possible count metric pairs"() {
    setup:
    List<PossibleMetric> possibleMetrics = []

    for (CiVisibilityCountMetric metric : CiVisibilityCountMetric.values()) {
      def metricTags = metric.getTags()
      // iterate over all possible combinations of metric tags
      for (TagValue[] tags : cartesianProduct(metricTags)) {
        possibleMetrics += new PossibleMetric(metric, tags)
      }
    }

    def collector = new CiVisibilityMetricCollector()

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
        assert metrics.contains(new CiVisibilityMetricData(firstMetric.metric.getName(), CiVisibilityMetricData.Type.COUNT, firstMetricValue, firstMetric.tags))
        assert metrics.contains(new CiVisibilityMetricData(secondMetric.metric.getName(), CiVisibilityMetricData.Type.COUNT, secondMetricValue, secondMetric.tags))
      }
    }
  }

  private Collection<TagValue[]> cartesianProduct(Class<? extends TagValue>[] sets) {
    Collection<TagValue[]> tuples = new ArrayList<>()
    cartesianProductBacktrack(sets, tuples, new ArrayDeque<>(), 0)
    return tuples
  }

  private void cartesianProductBacktrack(Class<? extends TagValue>[] sets, Collection<TagValue[]> tuples, Deque<TagValue> currentTuple, int offset) {
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
    cartesianProductBacktrack(sets, tuples, currentTuple, offset + 1)

    for (TagValue element : sets[offset].getEnumConstants()) {
      currentTuple.push(element)
      cartesianProductBacktrack(sets, tuples, currentTuple, offset + 1)
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
