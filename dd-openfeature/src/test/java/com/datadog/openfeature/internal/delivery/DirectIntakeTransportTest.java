package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.config.TestSettings;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class DirectIntakeTransportTest {
  private LocalHttpServer server;
  private final List<Long> sleeps = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    this.server = new LocalHttpServer();
  }

  @AfterEach
  void tearDown() {
    this.server.close();
  }

  @Test
  void requiresAnApiKey() {
    assertNull(DirectIntakeTransport.create(TestSettings.of()));
    assertNotNull(DirectIntakeTransport.create(TestSettings.of("api-key", "secret")));
  }

  @Test
  void postsJsonWithApiKeyAndTraceHeaders() throws Exception {
    this.server.enqueue(202, null);

    transport().post("exposures", "{\"a\":1}".getBytes(UTF_8));

    final LocalHttpServer.Request request = this.server.requests().get(0);
    assertEquals("POST", request.method);
    assertEquals("/api/v2/exposures", request.uri.getPath());
    assertEquals("{\"a\":1}", request.bodyAsString());
    assertEquals("application/json", request.header("Content-Type"));
    assertEquals("secret", request.header("dd-api-key"));
    assertNotNull(request.header("x-datadog-trace-id"));
    assertEquals(request.header("x-datadog-trace-id"), request.header("x-datadog-parent-id"));
    assertEquals("java", request.header("Datadog-Meta-Lang"));
    assertNull(request.header("X-Datadog-EVP-Subdomain"));
  }

  @TableTest({
    "scenario          | status",
    "server error      | 500   ",
    "unavailable       | 503   ",
    "too many requests | 429   "
  })
  void retriesRetryableStatus(final int status) throws Exception {
    this.server.enqueue(status, null).enqueue(status, null).enqueue(200, null);

    transport().post("flagevaluation", new byte[] {'{', '}'});

    assertEquals(3, this.server.requests().size());
    assertEquals(List.of(100L, 200L), this.sleeps);
  }

  @TableTest({
    "scenario    | status",
    "bad request | 400   ",
    "forbidden   | 403   ",
    "not found   | 404   ",
    "redirect    | 302   "
  })
  void doesNotRetryOtherFailures(final int status) {
    this.server.otherwise(status, null);

    assertThrows(IOException.class, () -> transport().post("exposures", new byte[] {'{', '}'}));

    assertEquals(1, this.server.requests().size());
  }

  @Test
  void givesUpAfterMaxRetries() {
    this.server.otherwise(503, null);

    assertThrows(IOException.class, () -> transport().post("exposures", new byte[] {'{', '}'}));

    assertEquals(DirectIntakeTransport.MAX_RETRIES + 1, this.server.requests().size());
    assertEquals(DirectIntakeTransport.MAX_RETRIES, this.sleeps.size());
  }

  @Test
  void retriesConnectionFailures() throws Exception {
    final URI unreachable;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      unreachable = URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/api/v2/");
    }
    final DirectIntakeTransport transport =
        new DirectIntakeTransport(
            unreachable, "secret", HttpClient.newHttpClient(), this.sleeps::add);

    assertThrows(IOException.class, () -> transport.post("exposures", new byte[] {'{', '}'}));

    assertEquals(DirectIntakeTransport.MAX_RETRIES, this.sleeps.size());
  }

  private DirectIntakeTransport transport() {
    return new DirectIntakeTransport(
        this.server.uri("/api/v2/"), "secret", HttpClient.newHttpClient(), this.sleeps::add);
  }
}
