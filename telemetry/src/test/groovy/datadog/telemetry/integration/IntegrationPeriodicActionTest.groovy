package datadog.telemetry.integration

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Integration
import datadog.trace.api.IntegrationsCollector
import spock.lang.Specification

class IntegrationPeriodicActionTest extends Specification {
  IntegrationPeriodicAction periodicAction = new IntegrationPeriodicAction()
  TelemetryService telemetryService = Mock()

  void 'push integrations into the telemetry service'() {
    setup:
    IntegrationsCollector.get().update(['web', 'jdbc'], true)

    when:
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addIntegration( { Integration integration ->
      integration.name == 'web' &&
        integration.enabled
    } )
    1 * telemetryService.addIntegration( { Integration integration ->
      integration.name == 'jdbc' &&
        integration.enabled
    } )
    0 * _._
  }
}
