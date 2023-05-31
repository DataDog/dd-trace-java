package datadog.trace.api.telemetry

import spock.lang.Specification

class MetricCollectorTest extends Specification {

  void 'test metric equality'() {
    when:
    final sameMetric = first == second

    then:
    sameMetric == expected

    where:
    first                                    | second                                   | expected
    metric(counter: 2)                       | metric(counter: 6)                       | true
    metric(counter: 2)                       | metric(common: false, counter: 6)        | false
    metric(counter: 2)                       | metric(namespace: 'other', counter: 6)   | false
    metric(counter: 2)                       | metric(metric: 'other', counter: 6)      | false
    metric(counter: 2)                       | metric(tags: ['a:b'], counter: 6)        | false
    metric(tags: ['a:b'], counter: 2)        | metric(tags: ['a:b'], counter: 6)        | true
    metric(tags: ['a:b'], counter: 2)        | metric(tags: ['c:d'], counter: 6)        | false
    metric(tags: ['a:b', 'c:d'], counter: 2) | metric(tags: ['a:b', 'c:d'], counter: 6) | true
  }

  private static MetricCollector.Metric metric(final Map metric) {
    metric.namespace = metric.namespace ?: 'namespace'
    metric.metric = metric.metric ?: 'metric'
    metric.common = metric.common == null ? true : metric.common
    metric.tags = metric.tags ?: []
    return new MetricCollector.Metric(metric.namespace as String, metric.common as boolean, metric.metric as String, metric.counter as Long, metric.tags as String[])
  }
}
