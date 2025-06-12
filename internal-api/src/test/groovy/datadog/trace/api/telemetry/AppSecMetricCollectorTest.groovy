package datadog.trace.api.telemetry

import datadog.trace.api.ConfigOrigin
import datadog.trace.test.util.DDSpecification

class AppSecMetricCollectorTest extends DDSpecification {

  def "test AppSec enabled metric collection"() {
    given:
    def collector = AppSecMetricCollector.get()

    when:
    collector.latestAppsecOrigin = ConfigOrigin.ENV
    collector.appSecEnabled()

    then:
    def metrics = collector.drain()
    metrics.size() == 1

    def enabledMetric = metrics[0] as AppSecMetricCollector.AppSecEnabledRawMetric
    enabledMetric.namespace == "appsec"
    enabledMetric.metricName == "enabled"
    enabledMetric.value == 1
    enabledMetric.tags.contains("origin:env_var")
  }

  def "test drain empties the queue"() {
    given:
    def collector = AppSecMetricCollector.get()

    when:
    collector.latestAppsecOrigin = ConfigOrigin.DEFAULT
    collector.appSecEnabled()
    def firstDrain = collector.drain()
    def secondDrain = collector.drain()

    then:
    firstDrain.size() == 1
    secondDrain.isEmpty()
  }

  def "test unknown origin handling"() {
    given:
    def collector = AppSecMetricCollector.get()

    when:
    collector.latestAppsecOrigin = null //reset
    collector.appSecEnabled()

    then:
    def metrics = collector.drain()
    def metric = metrics[0] as AppSecMetricCollector.AppSecEnabledRawMetric
    metric.tags.contains("origin:unknown")
  }
}
