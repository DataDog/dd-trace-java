package datadog.telemetry

import datadog.telemetry.api.DistributionSeries
import datadog.telemetry.api.Integration
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.Metric
import datadog.telemetry.dependency.Dependency
import ConfigOrigin
import ConfigSetting
import datadog.trace.api.telemetry.Endpoint
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
    !events.hasEndpoint()
  }

  def 'return added events'() {
    def events = new BufferedEvents()
    def configSetting = ConfigSetting.of("key", "value", ConfigOrigin.DEFAULT)
    def dependency = new Dependency("name", "version", "source", "hash")
    def series = new DistributionSeries()
    def integration = new Integration("integration-name", true)
    def logMessage = new LogMessage()
    def metric = new Metric()
    def endpoint = new Endpoint()

    when:
    events.addConfigChangeEvent(configSetting)

    then:
    !events.isEmpty()
    events.hasConfigChangeEvent()
    events.nextConfigChangeEvent() == configSetting
    !events.hasConfigChangeEvent()
    events.isEmpty()

    when:
    events.addDependencyEvent(dependency)

    then:
    !events.isEmpty()
    events.hasDependencyEvent()
    events.nextDependencyEvent() == dependency
    !events.hasDependencyEvent()
    events.isEmpty()

    when:
    events.addDistributionSeriesEvent(series)

    then:
    !events.isEmpty()
    events.hasDistributionSeriesEvent()
    events.nextDistributionSeriesEvent() == series
    !events.hasDistributionSeriesEvent()
    events.isEmpty()

    when:
    events.addIntegrationEvent(integration)

    then:
    !events.isEmpty()
    events.hasIntegrationEvent()
    events.nextIntegrationEvent() == integration
    !events.hasIntegrationEvent()
    events.isEmpty()

    when:
    events.addLogMessageEvent(logMessage)

    then:
    !events.isEmpty()
    events.hasLogMessageEvent()
    events.nextLogMessageEvent() == logMessage
    !events.hasLogMessageEvent()
    events.isEmpty()

    when:
    events.addMetricEvent(metric)

    then:
    !events.isEmpty()
    events.hasMetricEvent()
    events.nextMetricEvent() == metric
    !events.hasMetricEvent()
    events.isEmpty()

    when:
    events.addEndpointEvent(endpoint)

    then:
    !events.isEmpty()
    events.hasEndpoint()
    events.nextEndpoint() == endpoint
    !events.hasEndpoint()
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
    sink.addEndpointEvent(null)
  }
}
