package datadog.telemetry.endpoint

import datadog.telemetry.TelemetryService
import datadog.trace.api.telemetry.Endpoint
import datadog.trace.api.telemetry.EndpointCollector
import spock.lang.Specification

class EndpointPeriodicActionTest extends Specification {

  void 'test that endpoints are captured by the periodic action'() {
    given:
    final endpoint = new Endpoint().first(true).type('REST').method("GET").operation('http.request').path("/test")
    final service = Mock(TelemetryService)
    final endpointCollector = new EndpointCollector()
    endpointCollector.supplier([endpoint].iterator())
    final action = new EndpointPeriodicAction(endpointCollector)

    when:
    action.doIteration(service)

    then:
    1 * service.addEndpoint(endpoint)
    0 * _
  }

  void 'test that endpoints are not lost if the service is at capacity'() {
    given:
    final endpoints = [
      new Endpoint().first(true).type('REST').method("GET").operation('http.request').path("/test1"),
      new Endpoint().type('REST').method("GET").operation('http.request').path("/test2"),
      new Endpoint().type('REST').method("GET").operation('http.request').path("/test3"),
    ]
    final service = Mock(TelemetryService)
    final endpointCollector = new EndpointCollector()
    endpointCollector.supplier(endpoints.iterator())
    final action = new EndpointPeriodicAction(endpointCollector)

    when:
    action.doIteration(service)

    then:
    1 * service.addEndpoint(endpoints[0]) >> true
    1 * service.addEndpoint(endpoints[1]) >> false
    0 * _

    when:
    action.doIteration(service)

    then:
    1 * service.addEndpoint(endpoints[1]) >> true
    1 * service.addEndpoint(endpoints[2]) >> true
    0 * _
  }
}
