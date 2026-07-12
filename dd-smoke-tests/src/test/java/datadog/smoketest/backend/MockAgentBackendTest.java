package datadog.smoketest.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
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
  private static final OkHttpClient CLIENT = new OkHttpClient();

  // Recorded /v0.4/traces payload: 1 trace, 2 spans (netty.request -> WebController.hello),
  // service "smoke-test-java-app". Same fixture the decoder module's DecoderTest uses.
  private static byte[] v04Payload;

  private static MockAgentBackend backend;

  @BeforeAll
  static void setUp() throws IOException {
    v04Payload = readResource("/datadog/smoketest/backend/webflux.04.msgpack");
    backend = TraceBackend.mockAgent();
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
  void infoAdvertisesTraceEndpoints() throws IOException {
    Request request = new Request.Builder().url(backend.url() + "/info").get().build();
    try (Response response = CLIENT.newCall(request).execute()) {
      assertEquals(200, response.code());
      String body = response.body().string();
      assertTrue(body.contains("/v0.4/traces"), body);
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
