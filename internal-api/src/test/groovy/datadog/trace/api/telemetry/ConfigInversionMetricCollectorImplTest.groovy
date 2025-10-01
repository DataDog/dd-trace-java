package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

import static ConfigInversionMetricCollectorImpl.CONFIG_INVERSION_METRIC_NAME

class ConfigInversionMetricCollectorImplTest extends DDSpecification {

  def "should emit metric when unsupported env var is used"() {
    setup:
    def collector = ConfigInversionMetricCollectorImpl.getInstance()

    when:
    ConfigInversionMetricCollectorTestHelper.checkAndEmitUnsupported("FAKE_ENV_VAR")
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    assert metrics.size() == 1 : "The following Environment Variables need to be added to metadata/supported-configurations.json: ${metrics}"
    def metric = metrics[0]
    metric.type == 'count'
    metric.value == 1
    metric.namespace == 'tracers'
    metric.metricName == CONFIG_INVERSION_METRIC_NAME
    metric.tags.size() == 1
    metric.tags[0] == 'config_name:FAKE_ENV_VAR'
  }

  def "should not emit metric when supported env var is used"() {
    setup:
    def collector = ConfigInversionMetricCollectorImpl.getInstance()

    when:
    ConfigInversionMetricCollectorTestHelper.checkAndEmitUnsupported("DD_ENV")
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    assert metrics.isEmpty() : "The following Environment Variables need to be added to metadata/supported-configurations.json: ${metrics}"
  }
}
