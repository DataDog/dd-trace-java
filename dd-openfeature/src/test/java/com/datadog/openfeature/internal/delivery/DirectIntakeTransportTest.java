package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.config.TestSettings;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

public class DirectIntakeTransportTest {
  private LocalHttpServer server;

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
    "too many requests | 429   ",
    "bad request       | 400   ",
    "forbidden         | 403   ",
    "not found         | 404   ",
    "timeout           | 408   ",
    "redirect          | 302   "
  })
  void sendsFailedBatchesOnce(final int status) {
    this.server.otherwise(status, null);

    assertThrows(IOException.class, () -> transport().post("exposures", new byte[] {'{', '}'}));

    assertEquals(1, this.server.requests().size());
  }

  @TableTest({
    "scenario       | failure    ",
    "connection     | connection ",
    "timeout        | timeout    ",
    "TLS failure    | tls        ",
    "DNS failure    | dns        ",
    "interrupted IO | interrupted",
    "other IO       | other      "
  })
  void doesNotRetryIoFailures(final IOException failure) {
    final HttpClient client = mock(HttpClient.class);
    doReturn(CompletableFuture.failedFuture(failure)).when(client).sendAsync(any(), any());
    final DirectIntakeTransport transport =
        new DirectIntakeTransport(this.server.uri("/api/v2/"), "secret", client);

    assertThrows(failure.getClass(), () -> transport.post("exposures", new byte[] {'{', '}'}));

    verify(client, times(1)).sendAsync(any(), any());
  }

  /**
   * Creates an I/O failure for a send scenario.
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

  private DirectIntakeTransport transport() {
    return new DirectIntakeTransport(
        this.server.uri("/api/v2/"), "secret", HttpClient.newHttpClient());
  }
}
