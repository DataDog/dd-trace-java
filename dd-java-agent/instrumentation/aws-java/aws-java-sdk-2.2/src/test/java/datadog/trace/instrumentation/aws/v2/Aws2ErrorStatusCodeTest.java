package datadog.trace.instrumentation.aws.v2;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

/** Checks how the AWS SDK v2 span is tagged when the service answers with a non-2xx status. */
class Aws2ErrorStatusCodeTest extends AbstractInstrumentationTest {

  private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger();

  private static HttpServer server;

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body =
              ("<ErrorResponse><Error><Type>Sender</Type><Code>TestError</Code>"
                      + "<Message>mocked error</Message></Error>"
                      + "<RequestId>00000000-0000-0000-0000-000000000000</RequestId>"
                      + "</ErrorResponse>")
                  .getBytes(UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/xml");
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
  @ValueSource(
      ints = {
        301, 307, 400, 401, 402, 403, 404, 405, 406, 408, 409, 410, 411, 412, 413, 415, 416, 422,
        429, 499, 500, 501, 502, 503, 504
      })
  void serviceErrorSetsHttpStatusCode(int status) throws Exception {
    RESPONSE_STATUS.set(status);
    SqsClient client =
        SqsClient.builder()
            .endpointOverride(URI.create("http://localhost:" + server.getAddress().getPort()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("my-access-key", "my-secret-key")))
            .overrideConfiguration(c -> c.retryPolicy(RetryPolicy.none()))
            .build();

    SqsException thrown =
        assertThrows(
            SqsException.class,
            () -> client.createQueue(CreateQueueRequest.builder().queueName("somequeue").build()));
    assertEquals(status, thrown.statusCode());

    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);
    assertEquals(1, trace.size(), "expected a single aws span, got " + trace);
    DDSpan span = trace.get(0);
    assertAll(
        () -> assertEquals("Sqs.CreateQueue", span.getResourceName().toString()),
        () -> assertTrue(span.isError(), "span should be marked as error"),
        () -> assertEquals(status, span.getHttpStatusCode(), "http.status_code"));
    client.close();
  }
}
