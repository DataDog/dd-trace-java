package datadog.telemetry.endpoint;

import static datadog.trace.api.telemetry.Endpoint.Method.GET;
import static datadog.trace.api.telemetry.Endpoint.Operation.HTTP_REQUEST;
import static datadog.trace.api.telemetry.Endpoint.Type.REST;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.telemetry.TelemetryService;
import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.EndpointCollector;
import java.util.List;
import org.junit.jupiter.api.Test;

class EndpointPeriodicActionTest {

  @Test
  void testThatEndpointsAreCapturedByThePeriodicAction() {
    Endpoint endpoint =
        new Endpoint().first(true).type(REST).method(GET).operation(HTTP_REQUEST).path("/test");
    TelemetryService service = mock(TelemetryService.class);
    EndpointCollector endpointCollector = new EndpointCollector();
    endpointCollector.supplier(singletonList(endpoint).iterator());
    EndpointPeriodicAction action = new EndpointPeriodicAction(endpointCollector);

    action.doIteration(service);

    verify(service).addEndpoint(endpoint);
    verifyNoMoreInteractions(service);
  }

  @Test
  void testThatEndpointsAreNotLostIfTheServiceIsAtCapacity() {
    List<Endpoint> endpoints =
        asList(
            new Endpoint()
                .first(true)
                .type(REST)
                .method(GET)
                .operation(HTTP_REQUEST)
                .path("/test1"),
            new Endpoint().type(REST).method(GET).operation(HTTP_REQUEST).path("/test2"),
            new Endpoint().type(REST).method(GET).operation(HTTP_REQUEST).path("/test3"));
    TelemetryService service = mock(TelemetryService.class);
    EndpointCollector endpointCollector = new EndpointCollector();
    endpointCollector.supplier(endpoints.iterator());
    EndpointPeriodicAction action = new EndpointPeriodicAction(endpointCollector);

    // first iteration: the service accepts endpoints[0] but rejects endpoints[1] (at capacity)
    when(service.addEndpoint(endpoints.get(0))).thenReturn(true);
    when(service.addEndpoint(endpoints.get(1))).thenReturn(false);

    action.doIteration(service);

    verify(service).addEndpoint(endpoints.get(0));
    verify(service).addEndpoint(endpoints.get(1));
    verifyNoMoreInteractions(service);

    // second iteration: the rejected endpoints[1] is retried first, then endpoints[2] follows
    when(service.addEndpoint(endpoints.get(1))).thenReturn(true);
    when(service.addEndpoint(endpoints.get(2))).thenReturn(true);

    action.doIteration(service);

    verify(service, times(2)).addEndpoint(endpoints.get(1));
    verify(service).addEndpoint(endpoints.get(2));
    verifyNoMoreInteractions(service);
  }
}
