package datadog.trace.api.telemetry

import datadog.trace.api.ConfigCollector
import datadog.trace.api.IntegrationsCollector
import datadog.trace.api.metrics.SpanMetricRegistryImpl
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
    ConfigCollector.get().collect()

    when:
    ConfigCollector.get().put('key1', 'value1')
    ConfigCollector.get().put('key2', 'value2')
    ConfigCollector.get().put('key1', 'replaced')

    then:
    ConfigCollector.get().collect() == [key1: 'replaced', key2: 'value2']
  }

  def "no metrics - drain empty list"() {
    when:
    WafMetricCollector.get().prepareMetrics()

    then:
    WafMetricCollector.get().drain().isEmpty()
  }

  def "put-get waf metrics"() {
    when:
    WafMetricCollector.get().wafInit('waf_ver1', 'rules.1')
    WafMetricCollector.get().wafUpdates('rules.2')
    WafMetricCollector.get().wafUpdates('rules.3')
    WafMetricCollector.get().wafRequest()
    WafMetricCollector.get().wafRequest()
    WafMetricCollector.get().wafRequest()
    WafMetricCollector.get().wafRequestTriggered()
    WafMetricCollector.get().wafRequestBlocked()

    WafMetricCollector.get().prepareMetrics()

    then:
    def metrics = WafMetricCollector.get().drain()

    def initMetric = (WafMetricCollector.WafInitRawMetric)metrics[0]
    initMetric.type == 'count'
    initMetric.value == 1
    initMetric.namespace == 'appsec'
    initMetric.metricName == 'waf.init'
    initMetric.tags.toSet() == ['waf_version:waf_ver1', 'event_rules_version:rules.1'].toSet()

    def updateMetric1 = (WafMetricCollector.WafUpdatesRawMetric)metrics[1]
    updateMetric1.type == 'count'
    updateMetric1.value == 1
    updateMetric1.namespace == 'appsec'
    updateMetric1.metricName == 'waf.updates'
    updateMetric1.tags.toSet() == ['waf_version:waf_ver1', 'event_rules_version:rules.2'].toSet()

    def updateMetric2 = (WafMetricCollector.WafUpdatesRawMetric)metrics[2]
    updateMetric2.type == 'count'
    updateMetric2.value == 2
    updateMetric2.namespace == 'appsec'
    updateMetric2.metricName == 'waf.updates'
    updateMetric2.tags.toSet() == ['waf_version:waf_ver1', 'event_rules_version:rules.3'].toSet()

    def requestMetric = (WafMetricCollector.WafRequestsRawMetric)metrics[3]
    requestMetric.namespace == 'appsec'
    requestMetric.metricName == 'waf.requests'
    requestMetric.type == 'count'
    requestMetric.value == 3
    requestMetric.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.3',
      'rule_triggered:false',
      'request_blocked:false'
    ].toSet()

    def requestTriggeredMetric = (WafMetricCollector.WafRequestsRawMetric)metrics[4]
    requestTriggeredMetric.namespace == 'appsec'
    requestTriggeredMetric.metricName == 'waf.requests'
    requestTriggeredMetric.value == 1
    requestTriggeredMetric.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.3',
      'rule_triggered:true',
      'request_blocked:false'
    ].toSet()

    def requestBlockedMetric = (WafMetricCollector.WafRequestsRawMetric)metrics[5]
    requestBlockedMetric.namespace == 'appsec'
    requestBlockedMetric.metricName == 'waf.requests'
    requestBlockedMetric.type == 'count'
    requestBlockedMetric.value == 1
    requestBlockedMetric.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.3',
      'rule_triggered:true',
      'request_blocked:true'
    ].toSet()
  }

  def "overflowing WafMetricCollector does not crash"() {
    given:
    final limit = 1024
    def collector = WafMetricCollector.get()

    when:
    (0..limit*2).each {
      collector.wafInit("foo", "bar")
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit*2).each {
      collector.wafUpdates("bar")
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit*2).each {
      collector.wafRequest()
      collector.prepareMetrics()
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit*2).each {
      collector.wafRequestTriggered()
      collector.prepareMetrics()
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit*2).each {
      collector.wafRequestBlocked()
      collector.prepareMetrics()
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit
  }

  def "hide pii configuration data"() {
    setup:
    ConfigCollector.get().collect()

    when:
    ConfigCollector.get().put('DD_API_KEY', 'sensitive data')

    then:
    ConfigCollector.get().collect().get('DD_API_KEY') == '<hidden>'
  }

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
