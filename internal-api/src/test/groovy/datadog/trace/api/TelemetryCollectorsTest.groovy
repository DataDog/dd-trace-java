package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class TelemetryCollectorsTest extends DDSpecification {

  def "update-drain integrations"() {
    setup:
    IntegrationsCollector.integrations.offer(
      new IntegrationsCollector.Integration(
      names: ['spring'],
      enabled: true
      )
      )
    IntegrationsCollector.integrations.offer(
      new IntegrationsCollector.Integration(
      names: ['netty', 'jdbc'],
      enabled: false
      )
      )

    when:
    IntegrationsCollector.get().update(['netty', 'jetty'], true)

    then:
    IntegrationsCollector.get().drain() == ['spring': true, 'netty': true, 'jdbc': false, 'jetty': true]
    IntegrationsCollector.get().drain() == [:]
  }

  def "put-get configurations"() {
    setup:
    ConfigCollector.get().clear()

    when:
    ConfigCollector.get().put('key1', 'value1')
    ConfigCollector.get().put('key2', 'value2')
    ConfigCollector.get().put('key1', 'replaced')

    then:
    ConfigCollector.get() == [key1: 'replaced', key2: 'value2']
  }

  def "no metrics - drain empty list"() {
    when:
    MetricCollector.get().prepareRequestMetrics()

    then:
    MetricCollector.get().drain().isEmpty()
  }

  def "put-get waf metrics"() {
    when:
    MetricCollector.get().wafInit('waf_ver1', 'rules.1')
    MetricCollector.get().wafUpdates('rules.2')
    MetricCollector.get().wafUpdates('rules.3')
    MetricCollector.get().wafRequest()
    MetricCollector.get().wafRequest()
    MetricCollector.get().wafRequest()
    MetricCollector.get().wafRequestTriggered()
    MetricCollector.get().wafRequestBlocked()

    MetricCollector.get().prepareRequestMetrics()

    then:
    def metrics = MetricCollector.get().drain()

    def initMetric = (MetricCollector.WafInitRawMetric)metrics[0]
    initMetric.counter == 1
    initMetric.namespace == 'appsec'
    initMetric.metricName == 'waf.init'
    initMetric.wafVersion == 'waf_ver1'
    initMetric.rulesVersion == 'rules.1'

    def updateMetric1 = (MetricCollector.WafUpdatesRawMetric)metrics[1]
    updateMetric1.counter == 1
    updateMetric1.namespace == 'appsec'
    updateMetric1.metricName == 'waf.updates'
    updateMetric1.rulesVersion == 'rules.2'

    def updateMetric2 = (MetricCollector.WafUpdatesRawMetric)metrics[2]
    updateMetric2.counter == 2
    updateMetric2.namespace == 'appsec'
    updateMetric2.metricName == 'waf.updates'
    updateMetric2.rulesVersion == 'rules.3'

    def requestMetric = (MetricCollector.WafRequestsRawMetric)metrics[3]
    requestMetric.namespace == 'appsec'
    requestMetric.metricName == 'waf.requests'
    requestMetric.counter == 3
    !requestMetric.triggered        // false
    !requestMetric.blocked          // false

    def requestTriggeredMetric = (MetricCollector.WafRequestsRawMetric)metrics[4]
    requestTriggeredMetric.namespace == 'appsec'
    requestTriggeredMetric.metricName == 'waf.requests'
    requestTriggeredMetric.counter == 2
    requestTriggeredMetric.triggered      // true
    !requestTriggeredMetric.blocked       // false

    def requestBlockedMetric = (MetricCollector.WafRequestsRawMetric)metrics[5]
    requestBlockedMetric.namespace == 'appsec'
    requestBlockedMetric.metricName == 'waf.requests'
    requestBlockedMetric.counter == 1
    requestBlockedMetric.triggered        // true
    requestBlockedMetric.blocked          // true
  }

  def "hide pii configuration data"() {
    setup:
    ConfigCollector.get().clear()

    when:
    ConfigCollector.get().put('DD_API_KEY', 'sensitive data')

    then:
    ConfigCollector.get().get('DD_API_KEY') == '<hidden>'
  }
}
