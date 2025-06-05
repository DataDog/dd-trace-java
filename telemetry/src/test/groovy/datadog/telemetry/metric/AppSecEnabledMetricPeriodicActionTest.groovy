package datadog.telemetry.metric

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import datadog.trace.api.ConfigOrigin
import datadog.trace.api.telemetry.AppSecMetricCollector
import datadog.trace.test.util.DDSpecification

class AppSecEnabledMetricPeriodicActionTest extends DDSpecification {

  def "test AppSec metric periodic action"() {
    given:
    def telemetryService = Mock(TelemetryService)
    def action = new AppSecEnabledMetricPeriodicAction()
    def collector = AppSecMetricCollector.get()

    when:
    collector.latestAppsecOrigin = ConfigOrigin.ENV.name()
    collector.appSecEnabled()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      metric.namespace == 'appsec' &&
        metric.metric == 'enabled' &&
        metric.points[0][1] == 1 &&
        metric.tags.contains('origin:env_var') &&
        metric.type == Metric.TypeEnum.GAUGE
    })
    0 * _._
  }

  def "test collector reference"() {
    given:
    def action = new AppSecEnabledMetricPeriodicAction()

    when:
    def collector = action.collector()

    then:
    collector == AppSecMetricCollector.get()
  }
}
