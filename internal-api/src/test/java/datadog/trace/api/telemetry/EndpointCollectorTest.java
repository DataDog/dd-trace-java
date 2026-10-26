package datadog.trace.api.telemetry;

import static datadog.trace.api.telemetry.Endpoint.Method.METHODS;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class EndpointCollectorTest extends DDJavaSpecification {

  @Test
  void noMetricsDrainEmptyList() {
    // when
    Iterator<Endpoint> list = EndpointCollector.get().drain();

    // then
    assertFalse(list.hasNext());
  }

  @Test
  void drainSuppliedEndpointsViaIterator() {
    // setup
    List<String> methods = new ArrayList<>(METHODS);
    List<Endpoint> expected = new ArrayList<>();
    for (int index = 0; index <= 10; index++) {
      expected.add(
          new Endpoint()
              .first(index == 0)
              .type(Endpoint.Type.REST)
              .operation(Endpoint.Operation.HTTP_REQUEST)
              .path("/" + index)
              .requestBodyType(singletonList("application/json"))
              .responseBodyType(singletonList("plain/text"))
              .responseCode(singletonList(200))
              .authentication(singletonList("JWT"))
              .method(methods.get(index % methods.size())));
    }
    EndpointCollector.get().supplier(expected.iterator());

    // when
    List<Endpoint> received = new ArrayList<>();
    EndpointCollector.get().drain().forEachRemaining(received::add);

    // then
    assertEquals(expected, received);
  }
}
