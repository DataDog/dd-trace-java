package datadog.trace.api.telemetry

import datadog.trace.api.metrics.SpanMetricRegistryImpl
import datadog.trace.test.util.DDSpecification

class CoreMetricCollectorTest extends DDSpecification {
  def "update-drain span core metrics"() {
    setup:
    def spanMetrics = SpanMetricRegistryImpl.getInstance().get('datadog')
    spanMetrics.onSpanCreated()
    spanMetrics.onSpanCreated()
    spanMetrics.onSpanFinished()
    def collector = CoreMetricCollector.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 2

    def spanCreatedMetric = metrics[0]
    spanCreatedMetric.type == 'count'
    spanCreatedMetric.value == 2
    spanCreatedMetric.namespace == 'tracers'
    spanCreatedMetric.metricName == 'spans_created'
    spanCreatedMetric.tags == ['integration_name:datadog']

    def spanFinishedMetric = metrics[1]
    spanFinishedMetric.type == 'count'
    spanFinishedMetric.value == 1
    spanFinishedMetric.namespace == 'tracers'
    spanFinishedMetric.metricName == 'spans_finished'
    spanFinishedMetric.tags == ['integration_name:datadog']
  }

  def "overflowing core metrics"() {
    setup:
    def registry = SpanMetricRegistryImpl.getInstance()
    def collector = CoreMetricCollector.getInstance()
    final limit = 1024

    when:
    (0..limit*2).each {
      def spanMetrics = registry.get('instr-' + it)
      spanMetrics.onSpanCreated()
      spanMetrics.onSpanCreated()
      spanMetrics.onSpanFinished()
    }

    then:
    noExceptionThrown()
    collector.prepareMetrics()
    collector.drain().size() == limit
  }
}
