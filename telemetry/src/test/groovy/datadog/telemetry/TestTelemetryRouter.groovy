package datadog.telemetry

import datadog.communication.ddagent.TracerVersion
import datadog.http.client.HttpRequest
import datadog.http.client.HttpRequestBody
import datadog.telemetry.dependency.Dependency
import datadog.telemetry.api.Integration
import datadog.telemetry.api.DistributionSeries
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.Metric
import datadog.telemetry.api.RequestType
import datadog.trace.api.ConfigSetting
import datadog.trace.api.telemetry.Endpoint
import datadog.trace.api.telemetry.ProductChange
import groovy.json.JsonSlurper

class TestTelemetryRouter extends TelemetryRouter {
  private Queue<TelemetryClient.Result> mockResults = new LinkedList<>()
  private Queue<RequestAssertions> requests = new LinkedList<>()

  TestTelemetryRouter() {
    super(null, null, null, false)
  }

  @Override
  TelemetryClient.Result sendRequest(TelemetryRequest request) {
    if (mockResults.isEmpty()) {
      throw new IllegalStateException("Unexpected request has been sent. State expectations with `expectRequests` prior sending requests.")
    }

    def requestBuilder = request.httpRequest()
    requestBuilder.url("https://example.com")
    requests.add(new RequestAssertions(requestBuilder.build()))
    return mockResults.poll()
  }

  void expectRequest(TelemetryClient.Result mockResult) {
    expectRequests(1, mockResult)
  }

  void expectRequests(int requestNumber, TelemetryClient.Result mockResult) {
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

  BodyAssertions assertRequestBody(RequestType rt) {
    return assertRequest().headers(rt).assertBody().commonParts(rt)
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

    private HttpRequest request

    RequestAssertions(HttpRequest request) {
      this.request = request
    }

    RequestAssertions headers(RequestType requestType) {
      assert this.request.method() == 'POST'
      assert this.request.header('Content-Type') == 'application/json; charset=utf-8'
      assert this.request.header('DD-Client-Library-Language') == 'jvm'
      assert this.request.header('DD-Client-Library-Version') == TracerVersion.TRACER_VERSION
      assert this.request.header('DD-Telemetry-API-Version') == 'v2'
      assert this.request.header('DD-Telemetry-Request-Type') == requestType.toString()
      def entityId = this.request.header('Datadog-Entity-ID')
      assert entityId == null || entityId.startsWith("in-") || entityId.startsWith("cin-")
      return this
    }

    BodyAssertions assertBody() {
      HttpRequestBody body = this.request.body()
      ByteArrayOutputStream baos = new ByteArrayOutputStream()
      body.writeTo(baos)
      byte[] bytes = baos.toByteArray()
      def parsed = SLURPER.parse(bytes) as Map<String, Object>
      return new BodyAssertions(parsed, bytes)
    }
  }

  static class BodyAssertions {
    private final Map<String, Object> body
    private final byte[] bodyBytes

    BodyAssertions(Map<String, Object> body, byte[] bodyBytes) {
      this.body = body
      this.bodyBytes = bodyBytes
    }

    int bodySize() {
      return this.bodyBytes.length
    }

    BodyAssertions commonParts(RequestType requestType) {
      assert body['api_version'] == 'v2'

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
      def payload = body['payload'] as Map<String, Object>
      assert payload != null
      return new PayloadAssertions(payload)
    }

    BatchAssertions assertBatch(int expectedNumberOfPayloads) {
      List<Map<String, Object>> payloads = body['payload']
      assert payloads != null && payloads.size() == expectedNumberOfPayloads
      return new BatchAssertions(payloads)
    }

    void assertNoPayload() {
      assert body['payload'] == null
    }
  }

  static class BatchAssertions {
    private List<Map<String, Object>> messages

    BatchAssertions(List<Map<String, Object>> messages) {
      this.messages = messages
    }

    BatchMessageAssertions assertFirstMessage(RequestType expected) {
      return assertMessage(0, expected)
    }

    private BatchMessageAssertions assertMessage(int index, RequestType expected) {
      if (index > messages.size()) {
        throw new IllegalStateException("Asserted more messages than available (${messages.size()}) in the batch")
      }
      def message = messages[index]
      assert message['request_type'] == String.valueOf(expected)
      return new BatchMessageAssertions(this, index, message)
    }
  }

  static class BatchMessageAssertions {
    private BatchAssertions batchAssertions
    private int messageIndex
    private Map<String, Object> message

    BatchMessageAssertions(BatchAssertions batchAssertions, int messageIndex, Map<String, Object> message) {
      this.batchAssertions = batchAssertions
      this.messageIndex = messageIndex
      this.message = message
    }

    BatchMessageAssertions hasNoPayload() {
      assert message['payload'] == null
      return this
    }

    BatchMessageAssertions assertNextMessage(RequestType expected) {
      messageIndex += 1
      if (messageIndex >= batchAssertions.messages.size()) {
        throw new IllegalStateException("No more messages available")
      }
      return batchAssertions.assertMessage(messageIndex, expected)
    }

    PayloadAssertions hasPayload() {
      def payload = message['payload'] as Map<String, Object>
      assert payload != null
      return new PayloadAssertions(payload, this)
    }

    void assertNoMoreMessages() {
      assert messageIndex == batchAssertions.messages.size() - 1
    }
  }

  static class PayloadAssertions {
    private Map<String, Object> payload
    private BatchMessageAssertions batch

    PayloadAssertions(Map<String, Object> payload) {
      this(payload, null)
    }

    PayloadAssertions(Map<String, Object> payload, BatchMessageAssertions batch) {
      this.payload = payload
      this.batch = batch
    }

    PayloadAssertions configuration(List<ConfigSetting> configuration) {
      def expected = configuration == null ? null : []
      if (configuration != null) {
        for (ConfigSetting cs : configuration) {
          def item = [name: cs.normalizedKey(), value: cs.stringValue(), origin: cs.origin.value, 'seq_id': cs.seqId]
          expected.add(item)
        }
      }
      assert this.payload['configuration'] == expected
      return this
    }

    PayloadAssertions instrumentationConfigId(String id) {
      boolean checked = false
      this.payload['configuration'].each { v ->
        if (v['name'] == 'instrumentation_config_id') {
          assert v['value'] == id
          checked = true
        }
      }

      if (!checked) {
        assert id == null
      }

      return this
    }

    PayloadAssertions productChange(ProductChange product) {
      def name = product.getProductType().getName()
      def expected = [
        (name) : [enabled: product.isEnabled()]
      ]
      assert this.payload['products'] == expected
      return this
    }

    PayloadAssertions endpoint(final Endpoint... endpoints) {
      def expected = []
      endpoints.each {
        final item = [
          'operation_name': it.operation,
          'resource_name' : it.method + ' ' + it.path,
        ] as Map<String, Object>
        if (it.type) {
          item['type'] = it.type
        }
        if (it.method) {
          item['method'] = it.method
        }
        if (it.path) {
          item['path'] = it.path
        }
        if (it.requestBodyType) {
          item['request_body_type'] = it.requestBodyType
        }
        if (it.responseBodyType) {
          item['response_body_type'] = it.responseBodyType
        }
        if (it.authentication) {
          item['authentication'] = it.authentication
        }
        if (it.responseCode) {
          item['response_code'] = it.responseCode
        }
        if (it.metadata) {
          item['metadata'] = it.metadata
        }
        expected.add(item)
      }
      assert this.payload['endpoints'] == expected
      return this
    }

    PayloadAssertions products(boolean appsecEnabled = true, boolean profilerEnabled = false, boolean dynamicInstrumentationEnabled = false) {
      def expected = [
        appsec: [enabled: appsecEnabled],
        profiler: [enabled: profilerEnabled],
        dynamic_instrumentation: [enabled: dynamicInstrumentationEnabled]
      ]
      assert this.payload['products'] == expected
      return this
    }

    PayloadAssertions dependencies(List<Dependency> dependencies) {
      def expected = []
      for (Dependency d : dependencies) {
        expected.add([hash: d.hash, name: d.name, version: d.version])
      }
      assert this.payload['dependencies'] == expected
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
      assert this.payload['integrations'] == expected
      return this
    }

    PayloadAssertions namespace(String namespace) {
      assert this.payload['namespace'] == namespace
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
      assert this.payload['series'] == expected
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
      assert this.payload['series'] == expected
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
        map.put("count", l.getCount())
        expected.add(map)
      }
      assert this.payload['logs'] == expected
      return this
    }

    BatchMessageAssertions assertNextMessage(RequestType requestType) {
      return batch.assertNextMessage(requestType)
    }

    void assertNoMoreMessages() {
      batch.assertNoMoreMessages()
    }

    void installSignature(String installId, String installType, String installTime) {
      if (installId == null && installType == null && installTime == null) {
        assert this.payload['install_signature'] == null
        return
      }
      LinkedHashMap<String, String> expected = [:]
      if (installId != null) {
        expected.put("install_id", installId)
      }
      if (installType != null) {
        expected.put("install_type", installType)
      }
      if (installTime != null) {
        expected.put("install_time", installTime)
      }
      assert this.payload['install_signature'] == expected
    }
  }
}
