package datadog.telemetry.rum

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.DistributionSeries
import datadog.telemetry.api.Metric
import datadog.trace.api.rum.RumInjectorMetrics
import datadog.trace.api.rum.RumTelemetryCollector
import spock.lang.Specification

class RumPeriodicActionTest extends Specification {
  TelemetryService telemetryService = Mock()

  void 'push RUM metrics into the telemetry service'() {
    setup:
    def metricsCollector = new RumInjectorMetrics()
    metricsCollector.onInjectionSucceed("3")
    metricsCollector.onInjectionFailed("5", "gzip")
    metricsCollector.onInjectionResponseSize("3", 1024)

    def periodicAction = new RumPeriodicAction(metricsCollector)

    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      metric.namespace == "rum" &&
        metric.metric == "injection.succeed" &&
        metric.type == Metric.TypeEnum.COUNT
    })

    1 * telemetryService.addMetric({ Metric metric ->
      metric.namespace == "rum" &&
        metric.metric == "injection.failed" &&
        metric.type == Metric.TypeEnum.COUNT
    })

    1 * telemetryService.addDistributionSeries({ DistributionSeries dist ->
      dist.namespace == "rum" &&
        dist.metric == "injection.response.bytes"
    })

    0 * _
  }

  void 'push nothing when no metrics collector is set'() {
    setup:
    def periodicAction = new RumPeriodicAction(RumTelemetryCollector.NO_OP)

    when:
    periodicAction.doIteration(telemetryService)

    then:
    0 * telemetryService.addMetric(_)
    0 * telemetryService.addDistributionSeries(_)
    0 * _
  }
}
