package datadog.telemetry.metric


import datadog.telemetry.TelemetryService
import datadog.telemetry.api.DistributionSeries
import datadog.telemetry.api.Metric
import datadog.trace.api.telemetry.MetricCollector
import edu.umd.cs.findbugs.annotations.NonNull
import groovy.transform.NamedDelegate
import groovy.transform.NamedVariant
import spock.lang.Specification

class MetricPeriodicActionTest extends Specification {

  void 'test that common metrics are joined before being sent to telemetry #iterationIndex'() {
    given:
    final service = Mock(TelemetryService)
    final MetricCollector<MetricCollector.Metric> metricCollector = Mock(MetricCollector)
    final action = new DefaultMetricPeriodicAction(metricCollector)

    when:
    action.doIteration(service)

    then:
    metricCollector.drainDistributionSeries() >> []
    metricCollector.drain() >> metrics
    expected.each { Metric metric ->
      1 * service.addMetric({ it ->
        assertMetric(it, metric)
      })
    }
    0 * _

    where:
    metrics                                                                          | expected
    [col(counter: 2L)]                                                               | [tel(points: [[_, 2L]])]
    [col(counter: 2L), col(counter: 6L)]                                             | [tel(points: [[_, 2L], [_, 6L]])]
    [col(counter: 2L), col(namespace: 'other', counter: 6L)]                         | [tel(points: [[_, 2L]]), tel(namespace: 'other', points: [[_, 6L]])]
    [col(counter: 2L), col(common: false, counter: 6L)]                              | [tel(points: [[_, 2L]]), tel(common: false, points: [[_, 6L]])]
    [col(counter: 2L), col(metric: 'other', counter: 6L)]                            | [tel(points: [[_, 2L]]), tel(metric: 'other', points: [[_, 6L]])]
    [col(counter: 2L), col(tags: ['a:b'], counter: 6L)]                              | [tel(points: [[_, 2L]]), tel(tags: ['a:b'], points: [[_, 6L]])]
    [col(counter: 2L, tags: ['a:b']), col(counter: 6L, tags: ['c:d'])]               | [tel(points: [[_, 2L]], tags: ['a:b']), tel(points: [[_, 6L]], tags: ['c:d'])]
    [col(counter: 2L, tags: ['a:b', 'c:d']), col(counter: 6L, tags: ['a:b', 'c:d'])] | [tel(points: [[_, 2L], [_, 6L]], tags: ['a:b', 'c:d'])]
  }

  void 'test that common distribution series are joined before being sent to telemetry #iterationIndex'() {
    given:
    final service = Mock(TelemetryService)
    final MetricCollector<MetricCollector.Metric> metricCollector = Mock(MetricCollector)
    final action = new DefaultMetricPeriodicAction(metricCollector)

    when:
    action.doIteration(service)

    then:
    metricCollector.drain() >> []
    metricCollector.drainDistributionSeries() >> distributionSeries
    expected.each { DistributionSeries series ->
      1 * service.addDistributionSeries({ it ->
        assertDistributionSeries(it, series)
      })
    }
    0 * _

    where:
    distributionSeries                                                                     | expected
    [rawSeries(value: 2)]                                                                  | [series(points: [2])]
    [rawSeries(value: 2), rawSeries(value: 6)]                                             | [series(points: [2, 6])]
    [rawSeries(value: 2), rawSeries(namespace: 'other', value: 6)]                         | [series(points: [2]), series(namespace: 'other', points: [6])]
    [rawSeries(value: 2), rawSeries(common: false, value: 6)]                              | [series(points: [2]), series(common: false, points: [6])]
    [rawSeries(value: 2), rawSeries(metric: 'other', value: 6)]                            | [series(points: [2]), series(metric: 'other', points: [6])]
    [rawSeries(value: 2), rawSeries(tags: ['a:b'], value: 6)]                              | [series(points: [2]), series(tags: ['a:b'], points: [6])]
    [rawSeries(value: 2, tags: ['a:b']), rawSeries(value: 6, tags: ['c:d'])]               | [series(points: [2], tags: ['a:b']), series(points: [6], tags: ['c:d'])]
    [rawSeries(value: 2, tags: ['a:b', 'c:d']), rawSeries(value: 6, tags: ['a:b', 'c:d'])] | [series(points: [2, 6], tags: ['a:b', 'c:d'])]
  }

  private static MetricCollector.Metric col(final Map metric) {
    metric.namespace = metric.namespace ?: 'namespace'
    metric.metric = metric.metric ?: 'metric'
    metric.common = metric.common == null ? true : metric.common
    metric.tags = metric.tags ?: []
    return new MetricCollector.Metric(
      metric.namespace as String,
      metric.common as boolean,
      metric.metric as String,
      'count',
      metric.counter as Long,
      metric.tags as String[]
      )
  }

  @NamedVariant
  private static Metric tel(@NamedDelegate final Metric metric) {
    metric.namespace = metric.namespace ?: 'namespace'
    metric.metric = metric.metric ?: 'metric'
    metric.common = metric.common == null ? true : metric.common
    metric.tags = metric.tags ?: []
    metric.type = metric.type ?: Metric.TypeEnum.COUNT
    return metric
  }

  private static MetricCollector.DistributionSeriesPoint rawSeries(final Map point) {
    point.namespace = point.namespace ?: 'namespace'
    point.metric = point.metric ?: 'metric'
    point.common = point.common == null ? true : point.common
    point.tags = point.tags ?: []
    return new MetricCollector.DistributionSeriesPoint(
      point.metric as String,
      point.common as boolean,
      point.namespace as String,
      point.value as Integer,
      point.tags as List<String>
      )
  }

  private static DistributionSeries series(final Map distributionSeries) {
    return new DistributionSeries()
      .namespace(distributionSeries.namespace as String ?: 'namespace')
      .metric(distributionSeries.metric as String ?: 'metric')
      .common(distributionSeries.common == null ? true : distributionSeries.common as Boolean)
      .tags(distributionSeries.tags as List<String> ?: [])
      .points(distributionSeries.points as List<Integer>)
  }

  private static boolean assertMetric(Metric received, Metric expected) {
    if (!Objects.equals(received.namespace, expected.namespace)) {
      return false
    }
    if (!Objects.equals(received.metric, expected.metric)) {
      return false
    }
    if (!Objects.equals(received.common, expected.common)) {
      return false
    }
    if (!Objects.equals(received.type, expected.type)) {
      return false
    }
    if (!Objects.equals(received.tags, expected.tags)) {
      return false
    }
    if (received.points.size() != expected.points.size()) {
      return false
    }
    final expectedPoints = expected.points.collect { it[1] }
    final receivedPoints = received.points.collect { it[1] }
    return receivedPoints.containsAll(expectedPoints)
  }

  private static boolean assertDistributionSeries(DistributionSeries received, DistributionSeries expected) {
    return Objects.equals(received.namespace, expected.namespace) &&
      Objects.equals(received.metric, expected.metric) &&
      Objects.equals(received.common, expected.common) &&
      Objects.equals(received.tags, expected.tags) &&
      received.points.size() == expected.points.size() &&
      received.points.containsAll(expected.points)
  }

  class DefaultMetricPeriodicAction extends MetricPeriodicAction {

    private final MetricCollector<MetricCollector.Metric> collector

    DefaultMetricPeriodicAction(@NonNull final MetricCollector<MetricCollector.Metric> collector) {
      this.collector = collector
    }

    @Override
    @NonNull
    MetricCollector<MetricCollector.Metric> collector() {
      return collector
    }
  }
}
