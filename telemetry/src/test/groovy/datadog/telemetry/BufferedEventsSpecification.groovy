package datadog.telemetry

import datadog.telemetry.api.ConfigChange
import datadog.telemetry.api.DistributionSeries
import datadog.telemetry.api.Integration
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.Metric
import datadog.telemetry.dependency.Dependency
import datadog.trace.test.util.DDSpecification

class BufferedEventsSpecification extends DDSpecification {

  def 'empty events'() {
    def events = new BufferedEvents()

    expect:
    events.isEmpty()
    !events.hasConfigChangeEvent()
    !events.hasDependencyEvent()
    !events.hasDistributionSeriesEvent()
    !events.hasIntegrationEvent()
    !events.hasLogMessageEvent()
    !events.hasMetricEvent()
  }

  def 'return added events'() {
    def events = new BufferedEvents()
    def configChangeEvent = new ConfigChange("key", "value")
    def dependency = new Dependency("name", "version", "source", "hash")
    def series = new DistributionSeries()
    def integration = new Integration("integration-name", true)
    def logMessage = new LogMessage()
    def metric = new Metric()

    when:
    events.addConfigChangeEvent(configChangeEvent)
    events.addDependencyEvent(dependency)
    events.addDistributionSeriesEvent(series)
    events.addIntegrationEvent(integration)
    events.addLogMessageEvent(logMessage)
    events.addMetricEvent(metric)

    then:
    !events.isEmpty()

    events.hasConfigChangeEvent()
    events.nextConfigChangeEvent() == configChangeEvent
    !events.hasConfigChangeEvent()

    !events.isEmpty()

    events.hasDependencyEvent()
    events.nextDependencyEvent() == dependency
    !events.hasDependencyEvent()

    !events.isEmpty()

    events.hasDistributionSeriesEvent()
    events.nextDistributionSeriesEvent() == series
    !events.hasDistributionSeriesEvent()

    !events.isEmpty()

    events.hasIntegrationEvent()
    events.nextIntegrationEvent() == integration
    !events.hasIntegrationEvent()

    !events.isEmpty()

    events.hasLogMessageEvent()
    events.nextLogMessageEvent() == logMessage
    !events.hasLogMessageEvent()

    !events.isEmpty()

    events.hasMetricEvent()
    events.nextMetricEvent() == metric
    !events.hasMetricEvent()

    events.isEmpty()
  }

  def 'noop sink'() {
    def sink = EventSink.NOOP

    expect:
    sink.addMetricEvent(null)
    sink.addLogMessageEvent(null)
    sink.addIntegrationEvent(null)
    sink.addDistributionSeriesEvent(null)
    sink.addDependencyEvent(null)
    sink.addConfigChangeEvent(null)
  }
}
