package com.datadog.appsec.api.security.model

import com.datadog.appsec.api.security.json.EndpointsEncoding
import datadog.trace.api.appsec.api.security.model.Endpoint
import datadog.trace.test.util.DDSpecification
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode

import static datadog.trace.api.appsec.api.security.model.Endpoint.Method.POST
import static datadog.trace.api.appsec.api.security.model.Endpoint.Operation.HTTP_REQUEST
import static datadog.trace.api.appsec.api.security.model.Endpoint.Type.REST

class EndpointsEncodingTest extends DDSpecification {

  void 'test json encoding of endpoints'() {
    when:
    final json = EndpointsEncoding.toJson(test.v1)

    then:
    JSONAssert.assertEquals("Endpoints payload should match", test.v2, json, JSONCompareMode.NON_EXTENSIBLE)

    where:
    test << buildEndpoints()
  }

  static List<Tuple2<List<Endpoint>, String>> buildEndpoints() {
    return [
      Tuple.tuple([
        new Endpoint(type: REST,
        method: POST,
        path: '/analytics/requests',
        operation: HTTP_REQUEST,
        requestBodyType: ['application/json'],
        responseBodyType: ['application/json'],
        responseCode: [200, 201],
        authentication: ['JWT'],
        metadata: ['dotnet-ignore-anti-forgery': true, 'deprecated': true])
      ],
      """
{
  "endpoints": [
    {
      "type": "REST",
      "method": "POST",
      "path": "/analytics/requests",
      "operation-name": "http.request",
      "request-body-type": ["application/json"],
      "response-body-type": ["application/json"],
      "response-code": [200, 201],
      "authentication": ["JWT"],
      "metadata": {
        "dotnet-ignore-anti-forgery": true,
        "deprecated": true
      }
    }
  ]
}
""")
    ]
  }
}
