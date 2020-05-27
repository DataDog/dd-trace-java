import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.TraceUtils
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.spock.Testcontainers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.ListObjectsRequest
import software.amazon.awssdk.services.s3.model.ListObjectsResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import spock.lang.Shared

import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture

@Testcontainers
class S3PutObjectTest extends AgentTestRunner {
  @Shared
  LocalStackContainer localStack = new LocalStackContainer()
    .withServices(LocalStackContainer.Service.S3)

  @Shared
  S3AsyncClient s3Client

  @Shared
  Random random = new Random()

  def setup() {
    s3Client = S3AsyncClient
      .builder()
      .httpClientBuilder(
        NettyNioAsyncHttpClient.builder()
          .maxConcurrency(5)
          .connectionAcquisitionTimeout(Duration.of(2, ChronoUnit.MINUTES))
      )
      .endpointOverride(localStack.getEndpointOverride(LocalStackContainer.Service.S3))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
        localStack.getAccessKey(), localStack.getSecretKey()
      )))
      .region(Region.of(localStack.getRegion()))
      .build()

    s3Client.createBucket({ b -> b.bucket("test") }).get()

    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()
  }

  def "Send large file"() {
    given:
    byte[] bytes = new byte[1024 * 1024 * 50]
    random.nextBytes(bytes)
    File file = File.createTempFile("test", "data")
    FileOutputStream out = new FileOutputStream(file)
    out.write(bytes)
    out.close()

    List<CompletableFuture<PutObjectResponse>> responses = []

    when:
    for (int i = 0; i < 20; i++) {
      TraceUtils.runUnderTrace("put-$i") {
        responses.add(s3Client.putObject(PutObjectRequest.builder().bucket("test").key("test-$i").build() as PutObjectRequest,
          AsyncRequestBody.fromFile(file)))
      }
    }
    responses.forEach {
      it.get()
    }

    and:
    CompletableFuture<ListObjectsResponse> response
    TraceUtils.runUnderTrace("list") {
      response = s3Client.listObjects(ListObjectsRequest.builder().bucket("test").build() as ListObjectsRequest)
    }

    then:
    response.get().contents().findAll { it.key().startsWith("test-") }.size() == 20
    response.get().contents().any({ it.key() == "test-0" })
    response.get().contents().any({ it.key() == "test-19" })

    and:
    TEST_WRITER.size() == 21
  }
}
