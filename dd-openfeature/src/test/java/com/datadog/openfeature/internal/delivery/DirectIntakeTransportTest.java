package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.config.TestSettings;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

public class DirectIntakeTransportTest {
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
  void rejectsInvalidSite() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DirectIntakeTransport.create(
                TestSettings.of("api-key", "secret", "site", "datadoghq.com@other.example")));
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
    "timeout     | 408   ",
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

  @TableTest({
    "scenario     | reset               | requests | minDelay | maxDelay",
    "immediate    | 0                   | 2        | 0        | 400     ",
    "one second   | 1                   | 2        | 1000     | 1400    ",
    "maximum wait | 10                  | 2        | 10000    | 10400   ",
    "too long     | 11                  | 1        | 0        | 0       ",
    "overflow     | 9223372036854775807 | 1        | 0        | 0       ",
    "invalid      | invalid             | 3        | 100      | 100     ",
    "negative     | -2                  | 3        | 100      | 100     ",
    "empty        | ''                  | 3        | 100      | 100     "
  })
  void honorsRateLimitReset(
      final String reset, final int requests, final long minDelay, final long maxDelay)
      throws Exception {
    this.server
        .enqueue(429, null, "X-RateLimit-Reset", reset)
        .enqueue(503, null)
        .enqueue(200, null);

    if (requests == 3) {
      transport().post("exposures", new byte[] {'{', '}'});
    } else {
      assertThrows(IOException.class, () -> transport().post("exposures", new byte[] {'{', '}'}));
    }

    assertEquals(requests, this.server.requests().size());
    assertEquals(requests - 1, this.sleeps.size());
    if (!this.sleeps.isEmpty()) {
      assertTrue(this.sleeps.get(0) >= minDelay && this.sleeps.get(0) <= maxDelay);
    }
  }

  @TableTest({
    "scenario       | failure     | attempts",
    "connection     | connection  | 6       ",
    "timeout        | timeout     | 6       ",
    "TLS failure    | tls         | 1       ",
    "DNS failure    | dns         | 1       ",
    "interrupted IO | interrupted | 1       ",
    "other IO       | other       | 1       "
  })
  void retriesOnlyConnectionAndTimeoutFailures(final IOException failure, final int attempts)
      throws Exception {
    final HttpClient client = mock(HttpClient.class);
    doReturn(CompletableFuture.failedFuture(failure)).when(client).sendAsync(any(), any());
    doThrow(failure).when(client).send(any(), any());
    final DirectIntakeTransport transport =
        new DirectIntakeTransport(this.server.uri("/api/v2/"), "secret", client, this.sleeps::add);

    assertThrows(failure.getClass(), () -> transport.post("exposures", new byte[] {'{', '}'}));

    assertEquals(attempts - 1, this.sleeps.size());
  }

  /**
   * Creates an I/O failure for a retry scenario.
   *
   * @param value the failure kind.
   * @return the failure.
   */
  @TypeConverter
  public static IOException failure(final String value) {
    switch (value) {
      case "connection":
        return new ConnectException("connection refused");
      case "timeout":
        return new HttpTimeoutException("request timed out");
      case "tls":
        return new SSLException("TLS handshake failed");
      case "dns":
        return new UnknownHostException("unknown host");
      case "interrupted":
        return new InterruptedIOException("interrupted");
      default:
        return new IOException("I/O failure");
    }
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
