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
    events.nextConfigChangeEvent() == null
    events.nextDependencyEvent() == null
    events.nextDistributionSeriesEvent() == null
    events.nextIntegrationEvent() == null
    events.nextLogMessageEvent() == null
    events.nextMetricEvent() == null
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
    events.nextConfigChangeEvent() == configChangeEvent
    events.nextConfigChangeEvent() == null
    !events.isEmpty()
    events.nextDependencyEvent() == dependency
    events.nextDependencyEvent() == null
    !events.isEmpty()
    events.nextDistributionSeriesEvent() == series
    events.nextDistributionSeriesEvent() == null
    !events.isEmpty()
    events.nextIntegrationEvent() == integration
    events.nextIntegrationEvent() == null
    !events.isEmpty()
    events.nextLogMessageEvent() == logMessage
    events.nextLogMessageEvent() == null
    !events.isEmpty()
    events.nextMetricEvent() == metric
    events.nextMetricEvent() == null
    events.isEmpty()
  }

  def 'noop source'() {
    def src = EventSource.noop()

    expect:
    src.isEmpty()
    src.nextMetricEvent() == null
    src.nextLogMessageEvent() == null
    src.nextIntegrationEvent() == null
    src.nextDistributionSeriesEvent() == null
    src.nextDependencyEvent() == null
    src.nextConfigChangeEvent() == null
  }

  def 'noop sink'() {
    def sink = EventSink.noop()

    expect:
    sink.addMetricEvent(null)
    sink.addLogMessageEvent(null)
    sink.addIntegrationEvent(null)
    sink.addDistributionSeriesEvent(null)
    sink.addDependencyEvent(null)
    sink.addConfigChangeEvent(null)
  }
}
