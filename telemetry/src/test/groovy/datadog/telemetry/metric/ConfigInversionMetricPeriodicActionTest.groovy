package datadog.telemetry.metric

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import spock.lang.Specification

class ConfigInversionMetricPeriodicActionTest extends Specification{

  void 'test undocumented env var metric'() {
    setup:
    final telemetryService = Mock(TelemetryService)
    final action = new ConfigInversionMetricPeriodicAction()

    when:
    action.collector().setUndocumentedEnvVarMetric("DD_ENV_VAR")
    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      metric.namespace == 'tracers' &&
        metric.metric == 'untracked.config.detected' &&
        metric.points[0][1] == 1 &&
        metric.tags == ['config_name:DD_ENV_VAR'] &&
        metric.type == Metric.TypeEnum.COUNT
    })
    0 * _._
  }
}
