package datadog.trace.instrumentation.aws.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpServer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Lives in this source set because its S3 model (2.18.40) has {@code ExpectedBucketOwner}; the base
 * test suite pins s3 2.2.0 which predates the field.
 */
class S3BucketOwnerForkedTest extends AbstractInstrumentationTest {

  private static HttpServer server;
  private static S3Client client;

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    client =
        S3Client.builder()
            .endpointOverride(URI.create("http://localhost:" + server.getAddress().getPort()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();
  }

  @AfterAll
  static void stopServer() {
    client.close();
    server.stop(0);
  }

  @Test
  void expectedBucketOwnerTagsTheOwningAccount() throws Exception {
    client.getObject(
        GetObjectRequest.builder()
            .bucket("somebucket")
            .key("somekey")
            .expectedBucketOwner("123456789012")
            .build());

    DDSpan span = firstSpan();
    assertEquals("somebucket", span.getTag("aws.bucket.name"));
    assertEquals("123456789012", span.getTag("aws_account"));
  }

  @Test
  void noExpectedBucketOwnerMeansNoAccountTag() throws Exception {
    client.getObject(GetObjectRequest.builder().bucket("somebucket").key("somekey").build());

    DDSpan span = firstSpan();
    assertEquals("somebucket", span.getTag("aws.bucket.name"));
    assertFalse(span.getTags().containsKey("aws_account"));
  }

  @Test
  void malformedExpectedBucketOwnerIsIgnored() throws Exception {
    client.getObject(
        GetObjectRequest.builder()
            .bucket("somebucket")
            .key("somekey")
            .expectedBucketOwner("not-an-account")
            .build());

    assertFalse(firstSpan().getTags().containsKey("aws_account"));
  }

  private static DDSpan firstSpan() throws Exception {
    writer.waitForTraces(1);
    List<DDSpan> trace = writer.firstTrace();
    return trace.get(0);
  }
}
