package datadog.telemetry

import datadog.communication.ddagent.TracerVersion
import datadog.telemetry.dependency.Dependency
import datadog.telemetry.api.Integration
import datadog.telemetry.api.DistributionSeries

import datadog.telemetry.api.ConfigChange
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.Metric
import datadog.telemetry.api.RequestType
import groovy.json.JsonSlurper
import okhttp3.Request
import okio.Buffer

class TestHttpClient extends HttpClient {
  private Queue<Result> mockResults = new LinkedList<>()
  private Queue<RequestAssertions> requests = new LinkedList<>()

  TestHttpClient() {
    super(null)
  }

  @Override
  Result sendRequest(Request request) {
    if (mockResults.isEmpty()) {
      throw new IllegalStateException("Unexpected request has been sent. State expectations with `expectRequests` prior sending requests.")
    }
    requests.add(new RequestAssertions(request))
    return mockResults.poll()
  }

  void expectRequests(int requestNumber, Result mockResult) {
    for (int i=0; i < requestNumber; i++) {
      mockResults.add(mockResult)
    }
  }

  RequestAssertions assertRequest() {
    if (this.mockResults.size() > 0) {
      throw new IllegalStateException("Expected ${this.mockResults.size()} more sendRequest calls")
    }
    if (this.requests.size() == 0) {
      throw new IllegalStateException("No more requests have been sent.")
    }
    return this.requests.poll()
  }

  void assertRequests(int numberOfRequests) {
    for (int i=0; i < numberOfRequests; i++) {
      assertRequest()
    }
  }

  BodyAssertions assertRequestBody(RequestType rt) {
    return assertRequest().headers(rt)
      .assertBody().commonParts(rt)
  }

  void assertNoMoreRequests() {
    if (this.mockResults.size() > 0) {
      throw new IllegalStateException("Still expect ${this.mockResults.size()} request(s)")
    }
    if (this.requests.size() > 0) {
      throw new IllegalStateException("Still have ${this.requests.size()} requests when none expected.")
    }
  }

  static class RequestAssertions {
    private final static JsonSlurper SLURPER = new JsonSlurper()

    private Request request

    RequestAssertions(Request request) {
      this.request = request
    }

    RequestAssertions headers(RequestType requestType) {
      assert request.method() == 'POST'
      assert request.headers().names() == [
        'Content-Type',
        'DD-Client-Library-Language',
        'DD-Client-Library-Version',
        'DD-Telemetry-API-Version',
        'DD-Telemetry-Request-Type'
      ] as Set
      assert request.header('Content-Type') == 'application/json; charset=utf-8'
      assert request.header('DD-Client-Library-Language') == 'jvm'
      assert request.header('DD-Client-Library-Version') == TracerVersion.TRACER_VERSION
      assert request.header('DD-Telemetry-API-Version') == 'v1'
      assert request.header('DD-Telemetry-Request-Type') == requestType.toString()
      return this
    }

    BodyAssertions assertBody() {
      Buffer buf = new Buffer()
      request.body().writeTo(buf)
      byte[] bytes = new byte[buf.size()]
      buf.read(bytes)
      return new BodyAssertions(SLURPER.parse(bytes) as Map<String, Object>)
    }
  }

  static class BodyAssertions {
    private Map<String, Object> body

    BodyAssertions(Map<String, Object> body) {
      this.body = body
    }

    BodyAssertions commonParts(RequestType requestType) {
      assert body['api_version'] == 'v1'

      def app = body['application']
      assert app['env'] != null
      assert app['language_name'] == 'jvm'
      assert app['language_version'] =~ /\d+/
      assert app['runtime_name'] != null
      assert app['runtime_version'] != null
      assert app['service_name'] != null
      assert app['tracer_version'] == '0.42.0'

      def host = body['host']
      assert host['hostname'] != null
      assert host['os'] != null
      assert host['os_version'] != null
      assert host['kernel_name'] != null
      assert host['kernel_release'] != null
      assert host['kernel_version'] != null

      assert body['runtime_id'] =~ /[\da-f]{8}-([\da-f]{4}-){3}[\da-f]{12}/
      assert body['seq_id'] > 0
      assert body['tracer_time'] > 0
      assert body['request_type'] == requestType.toString()
      return this
    }

    PayloadAssertions assertPayload() {
      return new PayloadAssertions(body['payload'] as Map<String, Object>)
    }
  }

  static class PayloadAssertions {
    private Map<String, Object> payload

    PayloadAssertions(Map<String, Object> payload) {
      this.payload = payload
    }

    PayloadAssertions configuration(List<ConfigChange> configuration) {
      def expected = configuration == null ? null : []
      if (configuration != null) {
        for (ConfigChange kv : configuration) {
          expected.add([name: kv.name, value: kv.value])
        }
      }
      assert payload['configuration'] == expected
      return this
    }

    PayloadAssertions dependencies(List<Dependency> dependencies) {
      def expected = []
      for (Dependency d : dependencies) {
        expected.add([hash: d.hash, name: d.name, type: "PlatformStandard", version: d.version])
      }
      assert payload['dependencies'] == expected
      return this
    }

    PayloadAssertions integrations(List<Integration> integrations) {
      def expected = []
      for (Integration i : integrations) {
        Map map = new HashMap()
        map.put("enabled", i.enabled)
        map.put("name", i.name)
        expected.add(map)
      }
      assert payload['integrations'] == expected
      return this
    }

    PayloadAssertions namespace(String namespace) {
      assert payload['namespace'] == namespace
      return this
    }

    PayloadAssertions metrics(List<Metric> metrics) {
      def expected = []
      for (Metric m : metrics) {
        List<List<Number>> points = []
        for (List<Number> ps: m.getPoints()) {
          points.add(ps)
        }
        Map obj = new HashMap()
        obj.put("namespace", m.getNamespace())
        if (m.getCommon() != null) {
          obj.put("common", m.getCommon())
        }
        obj.put("metric", m.getMetric())
        obj.put("points", points)
        if (m.getType() != null) {
          obj.put("type", m.getType())
        }
        obj.put("tags", m.getTags())
        expected.add(obj)
      }
      assert payload['series'] == expected
      return this
    }

    PayloadAssertions distributionSeries(List<DistributionSeries> ds) {
      def expected = []
      for (DistributionSeries d : ds) {
        Map obj = new HashMap()
        obj.put("namespace", d.getNamespace())
        if (d.getCommon() != null) {
          obj.put("common", d.getCommon())
        }
        obj.put("metric", d.getMetric())
        obj.put("points", d.getPoints())
        obj.put("tags", d.getTags())
        expected.add(obj)
      }
      assert payload['series'] == expected
      return this
    }

    PayloadAssertions logs(List<LogMessage> ls) {
      def expected = []
      for (LogMessage l : ls) {
        Map map = new HashMap()
        map.put("message", l.getMessage())
        map.put("level", l.getLevel().toString())
        map.put("tags", l.getTags())
        if (l.getStackTrace() != null) {
          map.put("stack_trace", l.getStackTrace())
        }
        if (l.getTracerTime() != null) {
          map.put("tracer_time", l.getTracerTime())
        }
        expected.add(map)
      }
      assert payload['logs'] == expected
      return this
    }
  }
}
