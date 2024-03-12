import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.UploadPartRequest
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.core.datastreams.StatsGroup
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

abstract class AWS1S3ClientTest extends VersionedNamingTestBase {

  @Shared
  def credentialsProvider = new AWSStaticCredentialsProvider(new AnonymousAWSCredentials())
  @Shared
  def responseBody = new AtomicReference<String>()
  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      all {
        response.status(200).send(responseBody.get())
      }
    }
  }
  @Shared
  def endpoint = new AwsClientBuilder.EndpointConfiguration("http://localhost:$server.address.port", "us-west-2")

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  @Override
  protected long dataStreamsBucketDuration() {
    TimeUnit.MILLISECONDS.toNanos(250)
  }

  @Override
  String operation() {
    null
  }

  @Override
  String service() {
    null
  }

  def "send s3 request #testName with mocked response produces DSM checkpoints"() {
    setup:
    def conditions = new PollingConditions(timeout: 1)
    responseBody.set(body)
    AmazonS3 client = AmazonS3ClientBuilder.standard()
      .withPathStyleAccessEnabled(true)
      .withEndpointConfiguration(endpoint)
      .withCredentials(credentialsProvider)
      .build()

    when:
    def response = call.call(client)

    TEST_WRITER.waitForTraces(1)
    TEST_DATA_STREAMS_WRITER.waitForGroups(1)

    then:
    response != null

    conditions.eventually {
      List<StatsGroup> results = TEST_DATA_STREAMS_WRITER.groups.findAll { it.parentHash == 0 }
      assert results.size() >= 1
      results.each { group ->
        verifyAll(group) {
          edgeTags.containsAll([
            "direction:" + dsmDirection,
            "ds.name:somekey",
            "ds.namespace:somebucket",
            "topic:somebucket",
            "type:s3"
          ])
          edgeTags.size() == 5
          payloadSize.getCount() == 1
        }
      }
    }

    where:
    testName      | dsmDirection | call                                                                         | body
    "get object"  | "in"         | { AmazonS3 s3 -> s3.getObject("somebucket", "somekey") }                     | "somereponse"
    "put object"  | "out"        | { AmazonS3 s3 -> s3.putObject("somebucket", "somekey", "somecontent") }      | ""
    "upload part" | "out"        | { AmazonS3 s3 -> s3.uploadPart(new UploadPartRequest().withBucketName("somebucket").withKey("somekey").withUploadId("someid").withPartNumber(1).withPartSize(11).withInputStream(new ByteArrayInputStream("somecontent".getBytes()))) } | ""
  }
}

class AWS1S3ClientV0Test extends AWS1S3ClientTest {
  @Override
  int version() {
    0
  }
}

class AWS1S3ClientV1ForkedTest extends AWS1S3ClientTest {
  @Override
  int version() {
    1
  }
}
