package datadog.trace.api.telemetry

import spock.lang.Specification

class MetricCollectorTest extends Specification {

  void 'test metric equality #iterationIndex'() {
    when:
    final sameMetric = first == second

    then:
    sameMetric == expected

    where:
    first                                   | second                                  | expected
    counter(value: 2)                       | counter(value: 6)                       | true
    counter(value: 2)                       | counter(common: false, value: 6)        | false
    counter(value: 2)                       | counter(namespace: 'other', value: 6)   | false
    counter(value: 2)                       | counter(metric: 'other', value: 6)      | false
    counter(value: 2)                       | counter(tags: ['a:b'], value: 6)        | false
    counter(tags: ['a:b'], value: 2)        | counter(tags: ['a:b'], value: 6)        | true
    counter(tags: ['a:b'], value: 2)        | counter(tags: ['c:d'], value: 6)        | false
    counter(tags: ['a:b', 'c:d'], value: 2) | counter(tags: ['a:b', 'c:d'], value: 6) | true
  }

  void 'test metric toString #iterationIndex'() {
    expect:
    metric.toString() != null

    where:
    metric << [counter(value: 2), counter(namespace: 'other', value: 6), counter(tags: ['a:b', 'c:d'], value: 2)]
  }

  private static MetricCollector.Metric counter(final Map metric) {
    metric.namespace = metric.namespace ?: 'namespace'
    metric.metric = metric.metric ?: 'metric'
    metric.common = metric.common == null ? true : metric.common
    metric.tags = metric.tags ?: []
    return new MetricCollector.Metric(
      metric.namespace as String,
      metric.common as boolean,
      metric.metric as String,
      'count',
      metric.value as Number,
      metric.tags as String[]
      )
  }
}
