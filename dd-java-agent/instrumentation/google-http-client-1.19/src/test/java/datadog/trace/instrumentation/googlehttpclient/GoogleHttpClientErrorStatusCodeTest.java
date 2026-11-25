package datadog.trace.instrumentation.googlehttpclient;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.sun.net.httpserver.HttpServer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks how the Google HTTP client span is tagged when the server answers with a non-2xx status
 * and {@code HttpRequest.execute()} throws {@link HttpResponseException}, which is the default
 * behavior ({@code throwExceptionOnExecuteError = true}).
 */
class GoogleHttpClientErrorStatusCodeTest extends AbstractInstrumentationTest {

  private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger();

  private static HttpServer server;

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body = "{\"error\":\"mocked error\"}".getBytes(UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  @AfterAll
  static void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 409, 429, 500, 502, 503, 504})
  void executeErrorSetsHttpStatusCode(int status) throws Exception {
    RESPONSE_STATUS.set(status);
    HttpRequest request = buildRequest();

    HttpResponseException thrown = assertThrows(HttpResponseException.class, request::execute);
    assertEquals(status, thrown.getStatusCode());

    assertClientSpan(status);
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 500})
  void executeAsyncErrorSetsHttpStatusCode(int status) throws Exception {
    RESPONSE_STATUS.set(status);
    HttpRequest request = buildRequest();

    ExecutionException thrown =
        assertThrows(ExecutionException.class, () -> request.executeAsync().get());
    assertInstanceOf(HttpResponseException.class, thrown.getCause());
    assertEquals(status, ((HttpResponseException) thrown.getCause()).getStatusCode());

    assertClientSpan(status);
  }

  private static HttpRequest buildRequest() throws IOException {
    GenericUrl url = new GenericUrl("http://localhost:" + server.getAddress().getPort() + "/error");
    return new NetHttpTransport().createRequestFactory().buildGetRequest(url);
  }

  private static void assertClientSpan(int status) throws Exception {
    writer.waitForTraces(1);
    List<DDSpan> spans =
        writer.get(0).stream()
            .filter(span -> "google-http-client".equals(String.valueOf(span.getTag("component"))))
            .collect(Collectors.toList());
    assertEquals(1, spans.size(), "expected a single google-http-client span, got " + writer);
    DDSpan span = spans.get(0);
    assertAll(
        () -> assertEquals("GET /error", span.getResourceName().toString()),
        () -> assertTrue(span.isError(), "span should be marked as error"),
        () ->
            assertEquals(
                HttpResponseException.class.getName(),
                String.valueOf(span.getTag("error.type")),
                "error.type"),
        () -> assertEquals(status, span.getHttpStatusCode(), "http.status_code"));
  }
}
