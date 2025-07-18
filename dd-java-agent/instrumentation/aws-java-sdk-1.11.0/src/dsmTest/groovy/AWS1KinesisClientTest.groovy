import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.kinesis.AmazonKinesis
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.kinesis.model.GetRecordsRequest
import com.amazonaws.services.kinesis.model.PutRecordRequest
import com.amazonaws.services.kinesis.model.PutRecordsRequest
import com.amazonaws.services.kinesis.model.PutRecordsRequestEntry
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

abstract class AWS1KinesisClientTest extends VersionedNamingTestBase {

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
  final String streamName = "somestream"

  @Shared
  final String streamArn = "arnprefix:stream/" + streamName

  @Shared
  def timestamp = Instant.now().minusSeconds(60)

  @Shared
  def timestamp2 = timestamp.plusSeconds(1)

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // the actual service returns cbor encoded json
    System.setProperty("com.amazonaws.sdk.disableCbor", "true")
  }

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
    AmazonKinesis client = AmazonKinesisClientBuilder.standard()
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
          tags.hasAllTags("direction:" + dsmDirection, "topic:" + streamArn, "type:kinesis")
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
            "aws.stream.name" streamName
            "streamname" streamName
            "$DDTags.PATHWAY_HASH" { String }
            peerServiceFrom("aws.stream.name")
            defaultTags()
          }
        }
      }
    }

    where:
    service   | operation    | dsmDirection | dsmStatCount | method | path | call                                                                                                                                                                                                                                                                                                          | body
    "Kinesis" | "GetRecords" | "in"         | 1            | "POST" | "/"  | { AmazonKinesis c -> c.getRecords(new GetRecordsRequest().withStreamARN(streamArn)) }                                                                                                                                                                                                                         | """{
  "MillisBehindLatest": 2100,
  "NextShardIterator": "AAA",
  "Records": [
    {
      "Data": "XzxkYXRhPl8w",
      "PartitionKey": "partitionKey",
      "ApproximateArrivalTimestamp": ${timestamp.toEpochMilli()},
      "SequenceNumber": "21269319989652663814458848515492872193"
    }
  ]
}"""
    "Kinesis" | "GetRecords" | "in"         | 2            | "POST" | "/"  | { AmazonKinesis c -> c.getRecords(new GetRecordsRequest().withStreamARN(streamArn)) }                                                                                                                                                                                                                         | """{
  "MillisBehindLatest": 2100,
  "NextShardIterator": "AAA",
  "Records": [
    {
      "Data": "XzxkYXRhPl8w",
      "PartitionKey": "partitionKey",
      "ApproximateArrivalTimestamp": ${timestamp.toEpochMilli()},
      "SequenceNumber": "21269319989652663814458848515492872193"
    },
    {
      "Data": "XzxkYXRhPl8w",
      "PartitionKey": "partitionKey",
      "ApproximateArrivalTimestamp": ${timestamp2.toEpochMilli()},
      "SequenceNumber": "21269319989652663814458848515492872193"
    }
  ]
}"""
    "Kinesis" | "PutRecord"  | "out"        | 1            | "POST" | "/"  | { AmazonKinesis c -> c.putRecord(new PutRecordRequest().withStreamARN(streamArn).withData(ByteBuffer.wrap("message".getBytes(Charset.forName("UTF-8"))))) }                                                                                                                                                   | ""
    "Kinesis" | "PutRecords" | "out"        | 1            | "POST" | "/"  | { AmazonKinesis c -> c.putRecords(new PutRecordsRequest().withStreamARN(streamArn).withRecords(new PutRecordsRequestEntry().withData(ByteBuffer.wrap("message".getBytes(Charset.forName("UTF-8")))))) }                                                                                                       | ""
    "Kinesis" | "PutRecords" | "out"        | 2            | "POST" | "/"  | { AmazonKinesis c -> c.putRecords(new PutRecordsRequest().withStreamARN(streamArn).withRecords(new PutRecordsRequestEntry().withData(ByteBuffer.wrap("message".getBytes(Charset.forName("UTF-8")))), new PutRecordsRequestEntry().withData(ByteBuffer.wrap("message".getBytes(Charset.forName("UTF-8")))))) } | ""
  }
}

class AWS1KinesisClientV0Test extends AWS1KinesisClientTest {

  @Override
  String expectedOperation(String awsService, String awsOperation) {
    "aws.http"
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    return "java-aws-sdk"
  }

  @Override
  int version() {
    0
  }
}

class AWS1KinesisClientV1ForkedTest extends AWS1KinesisClientTest {

  @Override
  String expectedOperation(String awsService, String awsOperation) {
    return "aws.${awsService.toLowerCase()}.request"
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
