package datadog.telemetry.metric

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import datadog.trace.api.MetricCollector
import datadog.trace.test.util.DDSpecification

class MetricPeriodicActionSpecification extends DDSpecification {
  MetricPeriodicAction periodicAction = new MetricPeriodicAction()
  TelemetryService telemetryService = Mock()

  void 'push waf metrics into the telemetry service'() {
    setup:
    MetricCollector.get().wafInit('0.0.0', 'rules_ver_1')
    MetricCollector.get().wafUpdates('rules_ver_2')
    MetricCollector.get().wafUpdates('rules_ver_3')

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
        metric.tags == ['event_rules_version:rules_ver_2']
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.updates' &&
        metric.points[0][1] == 2 &&
        metric.tags == ['event_rules_version:rules_ver_3']
    } )
    0 * _._
  }

  void 'push waf request metrics and push into the telemetry'() {
    setup:
    MetricCollector.get().wafRequest()
    MetricCollector.get().wafRequestTriggered()
    MetricCollector.get().wafRequest()
    MetricCollector.get().wafRequestBlocked()
    MetricCollector.get().wafRequest()

    when:
    MetricCollector.get().prepareRequestMetrics()
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.requests' &&
        metric.points[0][1] == 3 &&
        metric.tags == ['triggered:false', 'blocked:false']
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.requests' &&
        metric.points[0][1] == 2 &&
        metric.tags == ['triggered:true', 'blocked:false']
    } )
    1 * telemetryService.addMetric( { Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'waf.requests' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['triggered:true', 'blocked:true']
    } )
    0 * _._
  }
}
