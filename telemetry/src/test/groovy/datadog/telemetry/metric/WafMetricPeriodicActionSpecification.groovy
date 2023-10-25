package datadog.telemetry.metric

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.test.util.DDSpecification

class WafMetricPeriodicActionSpecification extends DDSpecification {
  WafMetricPeriodicAction periodicAction = new WafMetricPeriodicAction()
  TelemetryService telemetryService = Mock()

  void 'push waf metrics into the telemetry service'() {
    setup:
    WafMetricCollector.get().wafInit('0.0.0', 'rules_ver_1')
    WafMetricCollector.get().wafUpdates('rules_ver_2')
    WafMetricCollector.get().wafUpdates('rules_ver_3')

    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.init' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['waf_version:0.0.0', 'event_rules_version:rules_ver_1']
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.updates' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['waf_version:0.0.0', 'event_rules_version:rules_ver_2']
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.updates' &&
        metric.points[0][1] == 2 &&
        metric.tags == ['waf_version:0.0.0', 'event_rules_version:rules_ver_3']
    } )
    0 * _._
  }

  void 'push waf request metrics and push into the telemetry'() {
    when:
    WafMetricCollector.get().wafInit('0.0.0', 'rules_ver_1')
    WafMetricCollector.get().wafRequest()
    WafMetricCollector.get().wafRequestTriggered()
    WafMetricCollector.get().wafRequest()
    WafMetricCollector.get().wafRequestBlocked()
    WafMetricCollector.get().wafRequest()
    WafMetricCollector.get().prepareMetrics()
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.init'
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.requests' &&
        metric.points[0][1] == 3 &&
        metric.tags == [
          'waf_version:0.0.0',
          'event_rules_version:rules_ver_1',
          'rule_triggered:false',
          'request_blocked:false'
        ]
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.requests' &&
        metric.points[0][1] == 1 &&
        metric.tags == [
          'waf_version:0.0.0',
          'event_rules_version:rules_ver_1',
          'rule_triggered:true',
          'request_blocked:false'
        ]
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.requests' &&
        metric.points[0][1] == 1 &&
        metric.tags == [
          'waf_version:0.0.0',
          'event_rules_version:rules_ver_1',
          'rule_triggered:true',
          'request_blocked:true'
        ]
    } )
    0 * _._

    when: 'waf.updates happens'
    WafMetricCollector.get().wafUpdates('rules_ver_2')
    WafMetricCollector.get().wafRequest()
    WafMetricCollector.get().wafRequestTriggered()
    WafMetricCollector.get().wafRequestBlocked()
    WafMetricCollector.get().prepareMetrics()
    periodicAction.doIteration(telemetryService)

    then: 'following waf.request have a new event_rules_version tag'
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.updates'
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.requests' &&
        metric.points[0][1] == 1 &&
        metric.tags == [
          'waf_version:0.0.0',
          'event_rules_version:rules_ver_2',
          'rule_triggered:false',
          'request_blocked:false'
        ]
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.requests' &&
        metric.points[0][1] == 1 &&
        metric.tags == [
          'waf_version:0.0.0',
          'event_rules_version:rules_ver_2',
          'rule_triggered:true',
          'request_blocked:false'
        ]
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.requests' &&
        metric.points[0][1] == 1 &&
        metric.tags == [
          'waf_version:0.0.0',
          'event_rules_version:rules_ver_2',
          'rule_triggered:true',
          'request_blocked:true'
        ]
    } )
    0 * _._
  }
}
