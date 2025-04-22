package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

class EndpointCollectorTest extends DDSpecification {

  void 'no metrics - drain empty list'() {
    when:
    final list = EndpointCollector.get().drain()

    then:
    !list.hasNext()
  }

  void 'set iterator'() {
    setup:
    final expected = (0..10).collect { index ->
      new Endpoint()
        .first(index == 0)
        .type(Endpoint.Type.REST)
        .operation(Endpoint.Operation.HTTP_REQUEST)
        .path("/$index")
        .requestBodyType(['application/json'])
        .responseBodyType(['plain/text'])
        .responseCode([200])
        .authentication(['JWT'])
        .method(Endpoint.Method.METHODS[index % Endpoint.Method.METHODS.size()])
    }
    EndpointCollector.get().supplier(expected.iterator())

    when:
    final received = EndpointCollector.get().drain().toList()

    then:
    received == expected
  }
}
