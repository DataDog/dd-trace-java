package datadog.telemetry.rum

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.DistributionSeries
import datadog.telemetry.api.Metric
import datadog.trace.api.rum.RumInjector
import datadog.trace.api.rum.RumInjectorMetrics
import spock.lang.Specification

class RumPeriodicActionTest extends Specification {
  RumPeriodicAction periodicAction = new RumPeriodicAction()
  TelemetryService telemetryService = Mock()

  void 'push RUM metrics into the telemetry service'() {
    setup:
    def metricsInstance = new RumInjectorMetrics()
    metricsInstance.onInjectionSucceed("3")
    metricsInstance.onInjectionFailed("5", "gzip")
    metricsInstance.onInjectionResponseSize("3", 1024)

    RumInjector.setTelemetryCollector(metricsInstance)

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

    cleanup:
    RumInjector.shutdownTelemetry()
  }

  void 'push nothing when no metrics collector is set'() {
    setup:
    RumInjector.shutdownTelemetry()

    when:
    periodicAction.doIteration(telemetryService)

    then:
    0 * telemetryService.addMetric(_)
    0 * telemetryService.addDistributionSeries(_)
    0 * _
  }
}
