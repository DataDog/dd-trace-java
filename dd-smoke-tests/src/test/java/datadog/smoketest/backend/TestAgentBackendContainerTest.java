package datadog.smoketest.backend;

import static datadog.smoketest.backend.TestAgentBackend.defaultImage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@link TestAgentBackend} against a real dd-apm-test-agent container.
 * Simulates a launched app by submitting the recorded v0.4 msgpack payload to {@code /v0.4/traces}
 * with the backend's session-token header, then verifies the backend reads and decodes exactly that
 * session's traces via {@code /test/session/*} (S3a / Q4a).
 */
class TestAgentBackendContainerTest {
  private static final MediaType MSGPACK = MediaType.parse("application/msgpack");
  private static final MediaType JSON = MediaType.parse("application/json");
  private static final OkHttpClient CLIENT = new OkHttpClient();

  private static byte[] v04Payload;
  private static TestAgentBackend backend;

  @BeforeAll
  static void setUp() throws IOException {
    v04Payload = readResource("/datadog/smoketest/backend/webflux.04.msgpack");
    backend = AgentBackend.testAgentBuilder().image(defaultImage()).build();
    backend.start();
  }

  @AfterAll
  static void tearDown() {
    if (backend != null) {
      backend.close();
    }
  }

  @BeforeEach
  void freshSession() {
    if (backend != null) {
      backend.clear();
    }
  }

  @Test
  void capturesSessionScopedTraces() throws IOException {
    submitAppTraces(backend.url(), backend.sessionToken(), v04Payload);

    Traces traces = backend.traces();
    traces.waitForTraceCount(1);

    List<DecodedTrace> decoded = traces.getTraces();
    assertEquals(1, decoded.size(), "trace count");

    List<DecodedSpan> spans = Decoder.sortByStart(decoded.get(0).getSpans());
    assertEquals(2, spans.size(), "span count");
    DecodedSpan root = spans.get(0);
    assertEquals("smoke-test-java-app", root.getService());
    assertEquals("netty.request", root.getName());
    assertEquals("GET /hello", root.getResource());
    assertEquals(0L, root.getParentId(), "root has no parent");
    assertEquals(root.getSpanId(), spans.get(1).getParentId(), "child parents the root");
  }

  @Test
  void clearStartsAFreshSession() throws IOException {
    submitAppTraces(backend.url(), backend.sessionToken(), v04Payload);
    backend.traces().waitForTraceCount(1);

    backend.clear();

    assertTrue(backend.traces().getTraces().isEmpty(), "clear() opens an empty session");
  }

  @Test
  void clearResetsRemoteConfig() throws IOException {
    // Push an RC config, confirm the agent serves it on /v0.7/config, then clear() and confirm the
    // session's RC response is reset to the tracer's empty "no configs" default.
    String path = "datadog/2/APM_TRACING/config_overrides/config";
    backend.remoteConfig().setConfig(path, "{\"lib_config\":{\"x\":1}}");

    String served = pollRemoteConfig(backend.url(), backend.sessionToken());
    assertTrue(served.contains(path), "config is served before clear: " + served);

    backend.clear();

    String afterClear = pollRemoteConfig(backend.url(), backend.sessionToken());
    assertFalse(
        afterClear.contains(path), "clear() reset the remote-config response: " + afterClear);
  }

  @Test
  void externalBackendReadsFromRunningAgent() throws IOException {
    // Point an .external() backend at the same running container: exercises the external code path
    // (no container of its own) and, via its own fresh token, that sessions are isolated.
    TestAgentBackend external =
        AgentBackend.testAgentBuilder().external(backend.url().getHost(), backend.port()).build();
    external.start();
    try {
      submitAppTraces(external.url(), external.sessionToken(), v04Payload);

      external.traces().waitForTraceCount(1);
      assertEquals(1, external.traces().getTraces().size(), "external reads only its own session");
    } finally {
      external.close();
    }
  }

  @Test
  void reportsTraceInvariantFailures() throws IOException {
    // Its own session token keeps the failure out of the class backend's session, whose teardown
    // asserts there are none (the agent only drops pooled failures on an explicit clear).
    TestAgentBackend external =
        AgentBackend.testAgentBuilder().external(backend.url().getHost(), backend.port()).build();
    external.start();
    try {
      // A trace-count header that disagrees with the payload violates trace_count_header.
      HttpUrl url = HttpUrl.get(external.url()).newBuilder().addPathSegments("v0.4/traces").build();
      Request request =
          new Request.Builder()
              .url(url)
              .header("X-Datadog-Trace-Count", "5")
              .header("Datadog-Meta-Tracer-Version", "0.0.0-smoke-test")
              .header("X-Datadog-Test-Session-Token", external.sessionToken())
              .put(RequestBody.create(MSGPACK, v04Payload))
              .build();
      try (Response response = CLIENT.newCall(request).execute()) {
        assertTrue(response.isSuccessful(), "submission still succeeds: HTTP " + response.code());
      }

      AssertionError error =
          assertThrows(AssertionError.class, external::assertNoInvariantFailures);
      assertTrue(error.getMessage().contains("trace_count_header"), error.getMessage());
    } finally {
      external.close();
    }
  }

  @Test
  void capturesTelemetry() throws IOException {
    // Post a telemetry app-started message; the backend reads it back from /test/apmtelemetry.
    HttpUrl url =
        HttpUrl.get(backend.url())
            .newBuilder()
            .addPathSegments("telemetry/proxy/api/v2/apmtelemetry")
            .build();
    Request request =
        new Request.Builder()
            .url(url)
            .header("DD-Telemetry-API-Version", "v2")
            .header("DD-Telemetry-Request-Type", "app-started")
            .header("X-Datadog-Test-Session-Token", backend.sessionToken())
            .post(
                RequestBody.create(
                    MediaType.parse("application/json"),
                    "{\"request_type\":\"app-started\",\"api_version\":\"v2\","
                        + "\"runtime_id\":\"r1\",\"seq_id\":1,\"payload\":{}}"))
            .build();
    try (Response response = CLIENT.newCall(request).execute()) {
      assertTrue(response.isSuccessful(), "telemetry accepted: HTTP " + response.code());
    }

    Telemetry telemetry = backend.telemetry();
    telemetry.waitForCount(1);
    assertTrue(
        telemetry.getFlatMessages().stream()
            .anyMatch(message -> "app-started".equals(message.get("request_type"))),
        "app-started telemetry captured");
  }

  private static String pollRemoteConfig(URI agentUrl, String token) throws IOException {
    // Poll /v0.7/config the way a tracer does; the agent returns the session's stored RC response.
    HttpUrl url = HttpUrl.get(agentUrl).newBuilder().addPathSegments("v0.7/config").build();
    Request request =
        new Request.Builder()
            .url(url)
            .header("X-Datadog-Test-Session-Token", token)
            .post(RequestBody.create(JSON, "{}"))
            .build();
    try (Response response = CLIENT.newCall(request).execute()) {
      assertTrue(response.isSuccessful(), "poll /v0.7/config: HTTP " + response.code());
      return response.body().string();
    }
  }

  private static void submitAppTraces(URI agentUrl, String token, byte[] payload)
      throws IOException {
    HttpUrl url = HttpUrl.get(agentUrl).newBuilder().addPathSegments("v0.4/traces").build();
    Request request =
        new Request.Builder()
            .url(url)
            .header("X-Datadog-Trace-Count", "1")
            .header("Datadog-Meta-Tracer-Version", "0.0.0-smoke-test")
            .header("X-Datadog-Test-Session-Token", token)
            .put(RequestBody.create(MSGPACK, payload))
            .build();
    try (Response response = CLIENT.newCall(request).execute()) {
      assertTrue(
          response.isSuccessful(), "test agent accepts trace submission: HTTP " + response.code());
    }
  }

  private static byte[] readResource(String name) throws IOException {
    try (InputStream in = TestAgentBackendContainerTest.class.getResourceAsStream(name)) {
      assertNotNull(in, "missing test resource " + name);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }
}
