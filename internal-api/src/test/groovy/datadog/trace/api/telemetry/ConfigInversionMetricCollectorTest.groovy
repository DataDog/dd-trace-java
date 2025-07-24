package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.telemetry.ConfigInversionMetricCollector.CONFIG_INVERSION_METRIC_NAME

class ConfigInversionMetricCollectorTest extends DDSpecification {

  def "should emit metric when unsupported env var is used"() {
    setup:
    def collector = ConfigInversionMetricCollector.getInstance()

    when:
    ConfigInversionMetricCollectorTestHelper.checkAndEmitUnsupported("DD_UNKNOWN_FEATURE")
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 1
    def metric = metrics[0]
    metric.type == 'count'
    metric.value == 1
    metric.namespace == 'tracers'
    metric.metricName == CONFIG_INVERSION_METRIC_NAME
    metric.tags.size() == 1
    metric.tags[0] == 'config_name:DD_UNKNOWN_FEATURE'
  }
}
