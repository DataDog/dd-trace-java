package datadog.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.ddagent.TracerVersion;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.ProductChange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import okhttp3.Request;
import okio.Buffer;

class TestTelemetryRouter extends TelemetryRouter {
  private final Queue<TelemetryClient.Result> mockResults = new LinkedList<>();
  private final Queue<RequestAssertions> requests = new LinkedList<>();

  TestTelemetryRouter() {
    super(null, null, null, false);
  }

  @Override
  public TelemetryClient.Result sendRequest(TelemetryRequest request) {
    if (mockResults.isEmpty()) {
      throw new IllegalStateException(
          "Unexpected request has been sent. State expectations with `expectRequests` prior sending requests.");
    }

    Request.Builder requestBuilder = request.httpRequest();
    requestBuilder.url("https://example.com");
    requests.add(new RequestAssertions(requestBuilder.build()));
    return mockResults.poll();
  }

  void expectRequest(TelemetryClient.Result mockResult) {
    expectRequests(1, mockResult);
  }

  void expectRequests(int requestNumber, TelemetryClient.Result mockResult) {
    for (int i = 0; i < requestNumber; i++) {
      mockResults.add(mockResult);
    }
  }

  RequestAssertions assertRequest() {
    if (!mockResults.isEmpty()) {
      throw new IllegalStateException("Expected " + mockResults.size() + " more sendRequest calls");
    }
    if (requests.isEmpty()) {
      throw new IllegalStateException("No more requests have been sent.");
    }
    return requests.poll();
  }

  BodyAssertions assertRequestBody(RequestType requestType) throws IOException {
    return assertRequest().headers(requestType).assertBody().commonParts(requestType);
  }

  void assertNoMoreRequests() {
    if (!mockResults.isEmpty()) {
      throw new IllegalStateException("Still expect " + mockResults.size() + " request(s)");
    }
    if (!requests.isEmpty()) {
      throw new IllegalStateException(
          "Still have " + requests.size() + " requests when none expected.");
    }
  }

  private static List<Double> toDoubles(List<? extends Number> numbers) {
    if (numbers == null) {
      return null;
    }
    List<Double> doubles = new ArrayList<>(numbers.size());
    for (Number number : numbers) {
      doubles.add(number.doubleValue());
    }
    return doubles;
  }

  static class RequestAssertions {
    private final Request request;

    RequestAssertions(Request request) {
      this.request = request;
    }

    RequestAssertions headers(RequestType requestType) {
      assertEquals("POST", request.method());
      assertTrue(
          request
              .headers()
              .names()
              .containsAll(
                  Arrays.asList(
                      "Content-Type",
                      "Content-Length",
                      "DD-Client-Library-Language",
                      "DD-Client-Library-Version",
                      "DD-Telemetry-API-Version",
                      "DD-Telemetry-Request-Type",
                      "DD-Session-ID")));
      assertEquals("application/json; charset=utf-8", request.header("Content-Type"));
      assertTrue(Integer.parseInt(request.header("Content-Length")) > 0);
      assertEquals("jvm", request.header("DD-Client-Library-Language"));
      assertEquals(TracerVersion.TRACER_VERSION, request.header("DD-Client-Library-Version"));
      assertEquals("v2", request.header("DD-Telemetry-API-Version"));
      assertEquals(requestType.toString(), request.header("DD-Telemetry-Request-Type"));
      String entityId = request.header("Datadog-Entity-ID");
      assertTrue(entityId == null || entityId.startsWith("in-") || entityId.startsWith("cin-"));
      String sessionId = request.header("DD-Session-ID");
      assertTrue(
          sessionId != null && sessionId.matches("[\\da-f]{8}-([\\da-f]{4}-){3}[\\da-f]{12}"));
      assertEquals(Config.get().getRuntimeId(), sessionId);
      // DD-Root-Session-ID should only be present when inherited from a parent process
      // (i.e., when rootSessionId != runtimeId). In normal test context, they're equal.
      String rootSessionId = request.header("DD-Root-Session-ID");
      if (Config.get().getRootSessionId().equals(Config.get().getRuntimeId())) {
        assertNull(rootSessionId);
      } else {
        assertEquals(Config.get().getRootSessionId(), rootSessionId);
      }
      return this;
    }

    @SuppressWarnings("unchecked")
    BodyAssertions assertBody() throws IOException {
      Buffer buf = new Buffer();
      request.body().writeTo(buf);
      byte[] bytes = new byte[(int) buf.size()];
      buf.read(bytes);
      Map<String, Object> parsed =
          (Map<String, Object>)
              new Moshi.Builder()
                  .build()
                  .adapter(Types.newParameterizedType(Map.class, String.class, Object.class))
                  .fromJson(new String(bytes, StandardCharsets.UTF_8));
      return new BodyAssertions(parsed, bytes);
    }
  }

  static class BodyAssertions {
    private final Map<String, Object> body;
    private final byte[] bodyBytes;

    BodyAssertions(Map<String, Object> body, byte[] bodyBytes) {
      this.body = body;
      this.bodyBytes = bodyBytes;
    }

    int bodySize() {
      return bodyBytes.length;
    }

    @SuppressWarnings("unchecked")
    BodyAssertions commonParts(RequestType requestType) {
      assertEquals("v2", body.get("api_version"));

      Map<String, Object> app = (Map<String, Object>) body.get("application");
      assertNotNull(app.get("env"));
      assertEquals("jvm", app.get("language_name"));
      assertTrue(((String) app.get("language_version")).matches("\\d+.*"));
      assertNotNull(app.get("runtime_name"));
      assertNotNull(app.get("runtime_version"));
      assertNotNull(app.get("service_name"));
      assertEquals("0.42.0", app.get("tracer_version"));

      Map<String, Object> host = (Map<String, Object>) body.get("host");
      assertNotNull(host.get("hostname"));
      assertNotNull(host.get("os"));
      assertNotNull(host.get("os_version"));
      assertNotNull(host.get("kernel_name"));
      assertNotNull(host.get("kernel_release"));
      assertNotNull(host.get("kernel_version"));

      assertTrue(
          ((String) body.get("runtime_id")).matches("[\\da-f]{8}-([\\da-f]{4}-){3}[\\da-f]{12}"));
      assertTrue(((Number) body.get("seq_id")).doubleValue() > 0);
      assertTrue(((Number) body.get("tracer_time")).doubleValue() > 0);
      assertEquals(requestType.toString(), body.get("request_type"));
      return this;
    }

    @SuppressWarnings("unchecked")
    PayloadAssertions assertPayload() {
      Map<String, Object> payload = (Map<String, Object>) body.get("payload");
      assertNotNull(payload);
      return new PayloadAssertions(payload);
    }

    @SuppressWarnings("unchecked")
    BatchAssertions assertBatch(int expectedNumberOfPayloads) {
      List<Map<String, Object>> payloads = (List<Map<String, Object>>) body.get("payload");
      assertNotNull(payloads);
      assertEquals(expectedNumberOfPayloads, payloads.size());
      return new BatchAssertions(payloads);
    }

    void assertNoPayload() {
      assertNull(body.get("payload"));
    }
  }

  static class BatchAssertions {
    private final List<Map<String, Object>> messages;

    BatchAssertions(List<Map<String, Object>> messages) {
      this.messages = messages;
    }

    BatchMessageAssertions assertFirstMessage(RequestType expected) {
      return assertMessage(0, expected);
    }

    private BatchMessageAssertions assertMessage(int index, RequestType expected) {
      if (index > messages.size()) {
        throw new IllegalStateException(
            "Asserted more messages than available (" + messages.size() + ") in the batch");
      }
      Map<String, Object> message = messages.get(index);
      assertEquals(String.valueOf(expected), message.get("request_type"));
      return new BatchMessageAssertions(this, index, message);
    }
  }

  static class BatchMessageAssertions {
    private final BatchAssertions batchAssertions;
    private int messageIndex;
    private final Map<String, Object> message;

    BatchMessageAssertions(
        BatchAssertions batchAssertions, int messageIndex, Map<String, Object> message) {
      this.batchAssertions = batchAssertions;
      this.messageIndex = messageIndex;
      this.message = message;
    }

    BatchMessageAssertions hasNoPayload() {
      assertNull(message.get("payload"));
      return this;
    }

    BatchMessageAssertions assertNextMessage(RequestType expected) {
      messageIndex += 1;
      if (messageIndex >= batchAssertions.messages.size()) {
        throw new IllegalStateException("No more messages available");
      }
      return batchAssertions.assertMessage(messageIndex, expected);
    }

    @SuppressWarnings("unchecked")
    PayloadAssertions hasPayload() {
      Map<String, Object> payload = (Map<String, Object>) message.get("payload");
      assertNotNull(payload);
      return new PayloadAssertions(payload, this);
    }

    void assertNoMoreMessages() {
      assertEquals(batchAssertions.messages.size() - 1, messageIndex);
    }
  }

  static class PayloadAssertions {
    private final Map<String, Object> payload;
    private final BatchMessageAssertions batch;

    PayloadAssertions(Map<String, Object> payload) {
      this(payload, null);
    }

    PayloadAssertions(Map<String, Object> payload, BatchMessageAssertions batch) {
      this.payload = payload;
      this.batch = batch;
    }

    PayloadAssertions configuration(List<ConfigSetting> configuration) {
      List<Map<String, Object>> expected = configuration == null ? null : new ArrayList<>();
      if (configuration != null) {
        for (ConfigSetting cs : configuration) {
          Map<String, Object> item = new HashMap<>();
          item.put("name", cs.key);
          item.put("value", cs.stringValue());
          item.put("origin", cs.origin.value);
          item.put("seq_id", (double) cs.seqId);
          expected.add(item);
        }
      }
      assertEquals(expected, payload.get("configuration"));
      return this;
    }

    @SuppressWarnings("unchecked")
    PayloadAssertions instrumentationConfigId(String id) {
      boolean checked = false;
      List<Map<String, Object>> configuration =
          (List<Map<String, Object>>) payload.get("configuration");
      for (Map<String, Object> entry : configuration) {
        if ("DD_INSTRUMENTATION_CONFIG_ID".equals(entry.get("name"))) {
          assertEquals(id, entry.get("value"));
          checked = true;
        }
      }
      if (!checked) {
        assertNull(id);
      }
      return this;
    }

    PayloadAssertions productChange(ProductChange product) {
      String name = product.getProductType().getName();
      Map<String, Object> expected = new HashMap<>();
      expected.put(name, Collections.singletonMap("enabled", product.isEnabled()));
      assertEquals(expected, payload.get("products"));
      return this;
    }

    PayloadAssertions endpoint(Endpoint... endpoints) {
      List<Map<String, Object>> expected = new ArrayList<>();
      for (Endpoint endpoint : endpoints) {
        Map<String, Object> item = new HashMap<>();
        item.put("operation_name", endpoint.getOperation());
        item.put("resource_name", endpoint.getMethod() + " " + endpoint.getPath());
        if (endpoint.getType() != null) {
          item.put("type", endpoint.getType());
        }
        if (endpoint.getMethod() != null) {
          item.put("method", endpoint.getMethod());
        }
        if (endpoint.getPath() != null) {
          item.put("path", endpoint.getPath());
        }
        if (endpoint.getRequestBodyType() != null) {
          item.put("request_body_type", endpoint.getRequestBodyType());
        }
        if (endpoint.getResponseBodyType() != null) {
          item.put("response_body_type", endpoint.getResponseBodyType());
        }
        if (endpoint.getAuthentication() != null) {
          item.put("authentication", endpoint.getAuthentication());
        }
        if (endpoint.getResponseCode() != null) {
          item.put("response_code", toDoubles(endpoint.getResponseCode()));
        }
        if (endpoint.getMetadata() != null) {
          item.put("metadata", endpoint.getMetadata());
        }
        expected.add(item);
      }
      assertEquals(expected, payload.get("endpoints"));
      return this;
    }

    PayloadAssertions products() {
      return products(true, false, false);
    }

    PayloadAssertions products(boolean appsecEnabled) {
      return products(appsecEnabled, false, false);
    }

    PayloadAssertions products(boolean appsecEnabled, boolean profilerEnabled) {
      return products(appsecEnabled, profilerEnabled, false);
    }

    PayloadAssertions products(
        boolean appsecEnabled, boolean profilerEnabled, boolean dynamicInstrumentationEnabled) {
      Map<String, Object> expected = new HashMap<>();
      expected.put("appsec", Collections.singletonMap("enabled", appsecEnabled));
      expected.put("profiler", Collections.singletonMap("enabled", profilerEnabled));
      expected.put(
          "dynamic_instrumentation",
          Collections.singletonMap("enabled", dynamicInstrumentationEnabled));
      assertEquals(expected, payload.get("products"));
      return this;
    }

    PayloadAssertions dependencies(List<Dependency> dependencies) {
      List<Map<String, Object>> expected = new ArrayList<>();
      for (Dependency dependency : dependencies) {
        Map<String, Object> item = new HashMap<>();
        item.put("hash", dependency.hash);
        item.put("name", dependency.name);
        item.put("version", dependency.version);
        expected.add(item);
      }
      assertEquals(expected, payload.get("dependencies"));
      return this;
    }

    PayloadAssertions integrations(List<Integration> integrations) {
      List<Map<String, Object>> expected = new ArrayList<>();
      for (Integration integration : integrations) {
        Map<String, Object> item = new HashMap<>();
        item.put("enabled", integration.enabled);
        item.put("name", integration.name);
        expected.add(item);
      }
      assertEquals(expected, payload.get("integrations"));
      return this;
    }

    PayloadAssertions namespace(String namespace) {
      assertEquals(namespace, payload.get("namespace"));
      return this;
    }

    PayloadAssertions metrics(List<Metric> metrics) {
      List<Map<String, Object>> expected = new ArrayList<>();
      for (Metric metric : metrics) {
        Map<String, Object> item = new HashMap<>();
        item.put("namespace", metric.getNamespace());
        if (metric.getCommon() != null) {
          item.put("common", metric.getCommon());
        }
        item.put("metric", metric.getMetric());
        List<List<Double>> points = new ArrayList<>();
        for (List<Number> point : metric.getPoints()) {
          points.add(toDoubles(point));
        }
        item.put("points", points);
        if (metric.getType() != null) {
          item.put("type", metric.getType());
        }
        item.put("tags", metric.getTags());
        expected.add(item);
      }
      assertEquals(expected, payload.get("series"));
      return this;
    }

    PayloadAssertions distributionSeries(List<DistributionSeries> distributionSeriesList) {
      List<Map<String, Object>> expected = new ArrayList<>();
      for (DistributionSeries distribution : distributionSeriesList) {
        Map<String, Object> item = new HashMap<>();
        item.put("namespace", distribution.getNamespace());
        if (distribution.getCommon() != null) {
          item.put("common", distribution.getCommon());
        }
        item.put("metric", distribution.getMetric());
        item.put("points", toDoubles(distribution.getPoints()));
        item.put("tags", distribution.getTags());
        expected.add(item);
      }
      assertEquals(expected, payload.get("series"));
      return this;
    }

    PayloadAssertions logs(List<LogMessage> logMessages) {
      List<Map<String, Object>> expected = new ArrayList<>();
      for (LogMessage logMessage : logMessages) {
        Map<String, Object> item = new HashMap<>();
        item.put("message", logMessage.getMessage());
        item.put("level", logMessage.getLevel().toString());
        item.put("tags", logMessage.getTags());
        if (logMessage.getStackTrace() != null) {
          item.put("stack_trace", logMessage.getStackTrace());
        }
        if (logMessage.getTracerTime() != null) {
          item.put("tracer_time", logMessage.getTracerTime().doubleValue());
        }
        item.put("count", (double) logMessage.getCount());
        expected.add(item);
      }
      assertEquals(expected, payload.get("logs"));
      return this;
    }

    BatchMessageAssertions assertNextMessage(RequestType requestType) {
      return batch.assertNextMessage(requestType);
    }

    void assertNoMoreMessages() {
      batch.assertNoMoreMessages();
    }

    void installSignature(String installId, String installType, String installTime) {
      if (installId == null && installType == null && installTime == null) {
        assertNull(payload.get("install_signature"));
        return;
      }
      Map<String, String> expected = new HashMap<>();
      if (installId != null) {
        expected.put("install_id", installId);
      }
      if (installType != null) {
        expected.put("install_type", installType);
      }
      if (installTime != null) {
        expected.put("install_time", installTime);
      }
      assertEquals(expected, payload.get("install_signature"));
    }
  }
}
