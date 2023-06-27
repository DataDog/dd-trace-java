package datadog.telemetry.metric


import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import datadog.trace.api.telemetry.MetricCollector
import edu.umd.cs.findbugs.annotations.NonNull
import groovy.transform.NamedDelegate
import groovy.transform.NamedVariant
import spock.lang.Specification

class MetricPeriodicActionTest extends Specification {

  void 'test that common metrics are joined before being sent to telemetry'() {
    given:
    final service = Mock(TelemetryService)
    final MetricCollector<MetricCollector.Metric> metricCollector = Mock(MetricCollector)
    final action = new DefaultMetricPeriodicAction(metricCollector)

    when:
    action.doIteration(service)

    then:
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

  private MetricCollector.Metric col(final Map metric) {
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
  private Metric tel(@NamedDelegate final Metric metric) {
    metric.namespace = metric.namespace ?: 'namespace'
    metric.metric = metric.metric ?: 'metric'
    metric.common = metric.common == null ? true : metric.common
    metric.tags = metric.tags ?: []
    metric.type = metric.type ?: Metric.TypeEnum.COUNT
    return metric
  }

  private boolean assertMetric(Metric received, Metric expected) {
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


  class DefaultMetricPeriodicAction extends MetricPeriodicAction {

    private final MetricCollector<Metric> collector

    DefaultMetricPeriodicAction(@NonNull final MetricCollector<Metric> collector) {
      this.collector = collector
    }

    @Override
    @NonNull
    MetricCollector<Metric> collector() {
      return collector
    }
  }
}
