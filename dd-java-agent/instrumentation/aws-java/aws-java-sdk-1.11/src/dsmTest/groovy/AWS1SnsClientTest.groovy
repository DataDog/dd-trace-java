import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sns.AmazonSNS
import com.amazonaws.services.sns.AmazonSNSClientBuilder
import com.amazonaws.services.sns.model.PublishBatchRequest
import com.amazonaws.services.sns.model.PublishBatchRequestEntry
import com.amazonaws.services.sns.model.PublishRequest
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

abstract class AWS1SnsClientTest extends VersionedNamingTestBase {

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

  @Shared
  final String topicName = "sometopic"

  @Shared
  final String topicArn = "arnprefix:" + topicName

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

  abstract String expectedOperation(String awsService, String awsOperation)

  abstract String expectedService(String awsService, String awsOperation)

  def "send #operation request with mocked response produces #dsmStatCount stat points"() {
    setup:
    def conditions = new PollingConditions(timeout: 1)
    responseBody.set(body)
    AmazonSNS client = AmazonSNSClientBuilder.standard()
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
      def pathwayLatencyCount = 0
      def edgeLatencyCount = 0
      results.each { group ->
        pathwayLatencyCount += group.pathwayLatency.count
        edgeLatencyCount += group.edgeLatency.count
        verifyAll(group) {
          tags.hasAllTags("direction:" + dsmDirection, "topic:" + topicName, "type:sns")
        }
      }
      verifyAll {
        pathwayLatencyCount == dsmStatCount
        edgeLatencyCount == dsmStatCount
      }
    }

    assertTraces(1) {
      trace(1) {
        span {
          serviceName expectedService(service, operation)
          operationName expectedOperation(service, operation)
          resourceName "$service.$operation"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "$server.address/"
            "$Tags.HTTP_METHOD" "$method"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" server.address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" { it.contains(service) }
            "aws_service" { it.contains(service.toLowerCase()) }
            "aws.endpoint" "$server.address"
            "aws.operation" "${operation}Request"
            "aws.agent" "java-aws-sdk"
            "aws.topic.name" topicName
            "topicname" topicName
            "$DDTags.PATHWAY_HASH" { String }
            peerServiceFrom("aws.topic.name")
            defaultTags()
          }
        }
      }
    }

    where:
    service | operation      | dsmDirection | dsmStatCount | method | path | call                                                                                                                                                                                                                         | body
    "SNS"   | "Publish"      | "out"        | 1            | "POST" | "/"  | { AmazonSNS c -> c.publish(new PublishRequest().withTopicArn(topicArn).withMessage("hello")) }                                                                                                                               | ""
    "SNS"   | "PublishBatch" | "out"        | 2            | "POST" | "/"  | { AmazonSNS c -> c.publishBatch(new PublishBatchRequest().withTopicArn(topicArn).withPublishBatchRequestEntries(new PublishBatchRequestEntry().withMessage("hello"), new PublishBatchRequestEntry().withMessage("world"))) } | ""
  }
}

class AWS1SnsClientV0Test extends AWS1SnsClientTest {

  @Override
  String expectedOperation(String awsService, String awsOperation) {
    "aws.http"
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    return "sns"
  }

  @Override
  int version() {
    0
  }
}

class AWS1SnsClientV1ForkedTest extends AWS1SnsClientTest {

  @Override
  String expectedOperation(String awsService, String awsOperation) {
    return "aws.${awsService.toLowerCase()}.send"
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    Config.get().getServiceName()
  }

  @Override
  int version() {
    1
  }
}
