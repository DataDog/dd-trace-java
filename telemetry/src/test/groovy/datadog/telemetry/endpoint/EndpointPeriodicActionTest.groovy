package datadog.telemetry.endpoint

import datadog.telemetry.TelemetryService
import datadog.trace.api.telemetry.Endpoint
import datadog.trace.api.telemetry.EndpointCollector
import spock.lang.Specification

class EndpointPeriodicActionTest extends Specification {

  void 'test that common metrics are joined before being sent to telemetry #iterationIndex'() {
    given:
    final endpoint = new Endpoint().first(true).type('REST').method("GET").operation('http.request').path("/test")
    final service = Mock(TelemetryService)
    final endpointCollector = Mock(EndpointCollector)
    final action = new EndpointPeriodicAction(endpointCollector)

    when:
    action.doIteration(service)

    then:
    1 * endpointCollector.drain() >> [endpoint].iterator()
    1 * service.addEndpoint(endpoint)
    0 * _
  }
}
