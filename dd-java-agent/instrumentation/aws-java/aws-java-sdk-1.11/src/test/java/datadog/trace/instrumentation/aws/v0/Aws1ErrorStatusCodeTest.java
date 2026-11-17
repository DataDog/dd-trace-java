package datadog.trace.instrumentation.aws.v0;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.sun.net.httpserver.HttpServer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks how the AWS SDK v1 span is tagged when the service answers with a non-2xx status. */
class Aws1ErrorStatusCodeTest extends AbstractInstrumentationTest {

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
          if ("HEAD".equals(exchange.getRequestMethod())) {
            // like S3, error responses to HEAD requests carry no body
            exchange.sendResponseHeaders(RESPONSE_STATUS.get(), -1);
            exchange.close();
            return;
          }
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
    AmazonSQS client =
        AmazonSQSClientBuilder.standard()
            .withEndpointConfiguration(
                new EndpointConfiguration(
                    "http://localhost:" + server.getAddress().getPort(), "us-east-1"))
            .withCredentials(
                new AWSStaticCredentialsProvider(
                    new BasicAWSCredentials("my-access-key", "my-secret-key")))
            .withClientConfiguration(new ClientConfiguration().withMaxErrorRetry(0))
            .build();

    AmazonServiceException thrown =
        assertThrows(AmazonServiceException.class, () -> client.createQueue("somequeue"));
    assertEquals(status, thrown.getStatusCode());

    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);
    assertEquals(1, trace.size(), "expected a single aws span, got " + trace);
    DDSpan span = trace.get(0);
    assertAll(
        () -> assertEquals("SQS.CreateQueue", span.getResourceName().toString()),
        () -> assertTrue(span.isError(), "span should be marked as error"),
        () -> assertEquals(status, span.getHttpStatusCode(), "http.status_code"));
    client.shutdown();
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        301, 307, 400, 401, 402, 403, 404, 405, 406, 408, 409, 410, 411, 412, 413, 415, 416, 422,
        429, 499, 500, 501, 502, 503, 504
      })
  void s3HeadErrorSetsHttpStatusCode(int status) throws Exception {
    RESPONSE_STATUS.set(status);
    AmazonS3 client =
        AmazonS3ClientBuilder.standard()
            .withEndpointConfiguration(
                new EndpointConfiguration(
                    "http://localhost:" + server.getAddress().getPort(), "us-east-1"))
            .withCredentials(
                new AWSStaticCredentialsProvider(
                    new BasicAWSCredentials("my-access-key", "my-secret-key")))
            .withClientConfiguration(new ClientConfiguration().withMaxErrorRetry(0))
            .withPathStyleAccessEnabled(true)
            .build();

    AmazonServiceException thrown =
        assertThrows(
            AmazonServiceException.class, () -> client.getObjectMetadata("somebucket", "somekey"));
    assertEquals(status, thrown.getStatusCode());

    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);
    assertEquals(1, trace.size(), "expected a single aws span, got " + trace);
    DDSpan span = trace.get(0);
    assertAll(
        () -> assertEquals("S3.GetObjectMetadata", span.getResourceName().toString()),
        () -> assertTrue(span.isError(), "span should be marked as error"),
        () -> assertEquals(status, span.getHttpStatusCode(), "http.status_code"));
  }
}
