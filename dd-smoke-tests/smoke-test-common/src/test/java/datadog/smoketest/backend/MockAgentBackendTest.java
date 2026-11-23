package datadog.smoketest.backend;

import static datadog.smoketest.trace.SpanMatcher.span;
import static datadog.smoketest.trace.TraceMatcher.trace;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.Moshi;
import datadog.remoteconfig.tuf.InstantJsonAdapter;
import datadog.remoteconfig.tuf.RawJsonAdapter;
import datadog.remoteconfig.tuf.RemoteConfigResponse;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.ByteString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the in-process {@link MockAgentBackend}: it must answer the tracer's {@code /info} probe,
 * accept the trace payloads the tracer PUTs, and decode them into the shared {@link DecodedTrace}
 * model. Drives the backend with a recorded v0.4 msgpack payload over real HTTP (okhttp), the same
 * way a launched app's tracer would (S2).
 *
 * <p>Uses a per-class backend cleared per method, mirroring the intended smoke-test lifecycle (Q3:
 * backend started once per class, reset between methods).
 */
class MockAgentBackendTest {
  private static final MediaType MSGPACK = MediaType.parse("application/msgpack");
  private static final MediaType JSON = MediaType.parse("application/json");
  private static final OkHttpClient CLIENT = new OkHttpClient();

  // Recorded /v0.4/traces payload: 1 trace, 2 spans (netty.request -> WebController.hello),
  // service "smoke-test-java-app". Same fixture the decoder module's DecoderTest uses.
  private static byte[] v04Payload;

  private static MockAgentBackend backend;

  @BeforeAll
  static void setUp() throws IOException {
    v04Payload = readResource("/datadog/smoketest/backend/webflux.04.msgpack");
    backend = new MockAgentBackend();
    backend.start();
  }

  @AfterAll
  static void tearDown() {
    if (backend != null) {
      backend.close();
    }
  }

  @BeforeEach
  void resetTraces() {
    backend.clear();
  }

  @Test
  void receivesAndDecodesSubmittedTraces() throws IOException {
    putTraces("/v0.4/traces", v04Payload);

    Traces traces = backend.traces();
    traces.waitForTraceCount(1);

    List<DecodedTrace> decoded = traces.getTraces();
    assertEquals(1, decoded.size(), "trace count");

    // sortByStart makes the assertion independent of the received span order (thin matcher is
    // positional — see TraceMatcher's TODO).
    List<DecodedSpan> spans = Decoder.sortByStart(decoded.get(0).getSpans());
    assertEquals(2, spans.size(), "span count");

    DecodedSpan root = spans.get(0);
    assertEquals("smoke-test-java-app", root.getService());
    assertEquals("netty.request", root.getName());
    assertEquals("GET /hello", root.getResource());
    assertEquals(0L, root.getParentId(), "root has no parent");
    assertEquals("netty", root.getMeta().get("component"));

    DecodedSpan child = spans.get(1);
    assertEquals("WebController.hello", child.getName());
    assertEquals(root.getSpanId(), child.getParentId(), "child parents the root span");
  }

  @Test
  void assertTracesFacadeMatchesDecodedTraces() throws IOException {
    putTraces("/v0.4/traces", v04Payload);

    // The fluent facade (S5) chains count-polling into the thin smoke matcher (S1). Both fixture
    // spans share the service and web type, so this holds regardless of the received span order.
    backend
        .traces()
        .waitForTraces(
            trace(
                span().service("smoke-test-java-app").type("web"),
                span().service("smoke-test-java-app").type("web")));
  }

  @Test
  void accumulatesTracesAcrossSubmissions() throws IOException {
    putTraces("/v0.4/traces", v04Payload);
    putTraces("/v0.4/traces", v04Payload);

    backend.traces().waitForTraceCount(2);
    assertEquals(2, backend.traces().getTraces().size());
  }

  @Test
  void clearDiscardsReceivedTraces() throws IOException {
    putTraces("/v0.4/traces", v04Payload);
    backend.traces().waitForTraceCount(1);

    backend.clear();

    assertTrue(backend.traces().getTraces().isEmpty(), "clear() drops collected traces");
  }

  @Test
  void capturesTelemetry() throws IOException {
    postTelemetry("{\"request_type\":\"app-started\",\"api_version\":\"v2\"}");
    postTelemetry(
        "{\"request_type\":\"message-batch\",\"payload\":["
            + "{\"request_type\":\"app-dependencies-loaded\"},"
            + "{\"request_type\":\"generate-metrics\"}]}");

    Telemetry telemetry = backend.telemetry();
    telemetry.waitForCount(2);
    assertEquals(2, telemetry.getMessages().size(), "raw messages: app-started + message-batch");

    // getFlatMessages expands the batch into its two entries: 1 + 2 = 3.
    List<Map<String, Object>> flat = telemetry.getFlatMessages();
    assertEquals(3, flat.size(), "message-batch expanded into its entries");
    assertTrue(
        flat.stream().anyMatch(m -> "app-started".equals(m.get("request_type"))),
        "app-started present");
    assertTrue(
        flat.stream().anyMatch(m -> "app-dependencies-loaded".equals(m.get("request_type"))),
        "batch entry present");

    backend.clear();
    assertTrue(backend.telemetry().getMessages().isEmpty(), "clear() drops telemetry too");
  }

  @Test
  void waitForFlatMatchesTelemetryEvents() throws IOException {
    postTelemetry("{\"request_type\":\"app-started\",\"api_version\":\"v2\"}");

    // Matches a received event…
    backend.telemetry().waitForFlat(message -> "app-started".equals(message.get("request_type")));
    // …and times out (short timeout) when nothing matches.
    assertThrows(
        AssertionError.class,
        () ->
            backend
                .telemetry()
                .waitForFlat(message -> "never-sent".equals(message.get("request_type")), 0.2));
  }

  @Test
  void infoAdvertisesTraceEndpointsAndCapabilities() throws IOException {
    Request request = new Request.Builder().url(backend.url() + "/info").get().build();
    try (Response response = CLIENT.newCall(request).execute()) {
      assertEquals(200, response.code());
      String body = response.body().string();
      assertTrue(body.contains("/v0.4/traces"), body);
      assertTrue(body.contains("\"client_drop_p0s\":true"), body);
    }
  }

  @Test
  void waitForTraceCountTimesOutWhenTooFew() {
    // Nothing submitted, so polling for a trace with a short timeout must fail rather than hang.
    assertThrows(
        AssertionError.class, () -> backend.traces().waitForTraceCount(1, 0.2 /* seconds */));
  }

  @Test
  void exposesBoundPort() {
    assertTrue(backend.port() > 0, "port is bound");
    assertEquals(backend.url().getPort(), backend.port(), "port matches url");
  }

  @Test
  void servesPushedRemoteConfigAndCapturesPolls() throws IOException {
    String path = "datadog/2/APM_TRACING/config_overrides/config";
    String config = "{\"lib_config\":{\"tracing_sampling_rate\":0.5}}";
    backend.remoteConfig().setConfig(path, config);

    String served =
        pollRemoteConfig("{\"client\":{\"products\":[\"APM_TRACING\"],\"capabilities\":[2]}}");

    // The served payload must satisfy the tracer's own parser, which checks each target file
    // against the sha256 and byte length declared in "targets".
    RemoteConfigResponse parsed =
        new RemoteConfigResponse.Factory(
                new Moshi.Builder()
                    .add(Instant.class, new InstantJsonAdapter())
                    .add(ByteString.class, new RawJsonAdapter())
                    .build())
            .fromInputStream(new ByteArrayInputStream(served.getBytes(UTF_8)))
            .orElseThrow(() -> new AssertionError("remote-config payload not parsed: " + served));
    assertEquals(config, new String(parsed.getFileContents(path), UTF_8), "config content");

    List<Map<String, Object>> polls = backend.remoteConfig().requests();
    assertEquals(1, polls.size(), "the poll was captured");
    assertTrue(RemoteConfig.products(polls.get(0)).contains("APM_TRACING"), "products decoded");
    assertEquals(2L, RemoteConfig.capabilities(polls.get(0)), "capabilities decoded");
  }

  @Test
  void clearResetsRemoteConfig() throws IOException {
    backend.remoteConfig().setConfig("datadog/2/APM_TRACING/config_overrides/config", "{\"a\":1}");
    assertTrue(pollRemoteConfig("{}").contains("client_configs"), "config served before clear");

    backend.clear();

    assertTrue(backend.remoteConfig().requests().isEmpty(), "captured polls dropped");
    assertEquals("{}", pollRemoteConfig("{}"), "back to the no-configs response");
  }

  private static String pollRemoteConfig(String clientBody) throws IOException {
    Request request =
        new Request.Builder()
            .url(backend.url() + "/v0.7/config")
            .post(RequestBody.create(JSON, clientBody))
            .build();
    try (Response response = CLIENT.newCall(request).execute()) {
      assertEquals(200, response.code(), "mock agent should serve remote config");
      return response.body().string();
    }
  }

  private static void postTelemetry(String json) throws IOException {
    Request request =
        new Request.Builder()
            .url(backend.url() + "/telemetry/proxy/api/v2/apmtelemetry")
            .post(RequestBody.create(JSON, json))
            .build();
    try (Response response = CLIENT.newCall(request).execute()) {
      assertTrue(response.isSuccessful(), "mock agent should accept telemetry: " + response.code());
    }
  }

  @Test
  void surfacesDecodingFailures() throws IOException {
    // Decoding runs on the server thread, where JavaTestHttpServer turns the exception into a 500;
    // without capture the test would only see an empty trace collection and time out.
    Request request =
        new Request.Builder()
            .url(backend.url() + "/v0.4/traces")
            .put(RequestBody.create(MSGPACK, "not msgpack".getBytes(UTF_8)))
            .build();
    try (Response response = CLIENT.newCall(request).execute()) {
      assertEquals(500, response.code(), "malformed payload is rejected");
    }

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> backend.traces().getTraces());
    assertTrue(error.getMessage().contains("decode received traces"), error.getMessage());
    assertNotNull(error.getCause(), "keeps the decoder failure as the cause");

    // The other surfaces stay usable — the failures are tracked per surface.
    assertTrue(backend.telemetry().getMessages().isEmpty(), "telemetry unaffected");
    assertTrue(backend.remoteConfig().requests().isEmpty(), "remote config unaffected");

    backend.clear();
    assertTrue(backend.traces().getTraces().isEmpty(), "clear() drops the failure too");
  }

  private static void putTraces(String path, byte[] payload) throws IOException {
    Request request =
        new Request.Builder()
            .url(backend.url() + path)
            .put(RequestBody.create(MSGPACK, payload))
            .build();
    try (Response response = CLIENT.newCall(request).execute()) {
      assertEquals(200, response.code(), "mock agent should accept trace submissions");
    }
  }

  private static byte[] readResource(String name) throws IOException {
    try (InputStream in = MockAgentBackendTest.class.getResourceAsStream(name)) {
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
