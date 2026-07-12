package datadog.smoketest.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.testcontainers.DockerClientFactory;

/**
 * End-to-end tests for {@link TestAgentBackend} against a real dd-apm-test-agent container. Skipped
 * (aborted) when Docker is unavailable. Simulates a launched app by submitting the recorded v0.4
 * msgpack payload to {@code /v0.4/traces} with the backend's session-token header, then verifies
 * the backend reads and decodes exactly that session's traces via {@code /test/session/*} (S3a /
 * Q4a).
 *
 * <p>Uses the public {@code ghcr.io} image so it runs without internal-registry access; real smoke
 * tests default to the CI mirror (see {@link TestAgentBackend}).
 */
class TestAgentBackendContainerTest {
  private static final String PUBLIC_IMAGE =
      "ghcr.io/datadog/dd-apm-test-agent/ddapm-test-agent:v1.44.0";
  private static final MediaType MSGPACK = MediaType.parse("application/msgpack");
  private static final OkHttpClient CLIENT = new OkHttpClient();

  private static byte[] v04Payload;
  private static TestAgentBackend backend;

  @BeforeAll
  static void setUp() throws IOException {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker is required for the test-agent container backend");
    v04Payload = readResource("/datadog/smoketest/backend/webflux.04.msgpack");
    backend = TraceBackend.testAgent().image(PUBLIC_IMAGE).build();
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
  void externalBackendReadsFromRunningAgent() throws IOException {
    // Point an .external() backend at the same running container: exercises the external code path
    // (no container of its own) and, via its own fresh token, that sessions are isolated.
    TestAgentBackend external =
        TraceBackend.testAgent().external(backend.url().getHost(), backend.port()).build();
    external.start();
    try {
      submitAppTraces(external.url(), external.sessionToken(), v04Payload);

      external.traces().waitForTraceCount(1);
      assertEquals(1, external.traces().getTraces().size(), "external reads only its own session");
    } finally {
      external.close();
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
