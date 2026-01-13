import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import datadog.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory
import datadog.eclipse.jetty.server.HttpConfiguration
import datadog.eclipse.jetty.server.HttpConnectionFactory
import datadog.eclipse.jetty.server.Server
import datadog.eclipse.jetty.server.ServerConnector
import datadog.eclipse.jetty.server.SslConnectionFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.interceptor.Context
import software.amazon.awssdk.core.interceptor.ExecutionAttributes
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.http.Protocol
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

abstract class Aws2KinesisDataStreamsTest extends VersionedNamingTestBase {

  private static final StaticCredentialsProvider CREDENTIALS_PROVIDER = StaticCredentialsProvider
  .create(AwsBasicCredentials.create("my-access-key", "my-secret-key"))

  @Shared
  def responseBody = new AtomicReference<String>()
  @Shared
  def servedRequestId = new AtomicReference<String>()

  @Shared
  def timestamp = Instant.now().minusSeconds(60)

  @Shared
  def timestamp2 = timestamp.plusSeconds(1)

  @AutoCleanup
  @Shared
  def server = httpServer {
    customizer { {
        Server server -> {
          ServerConnector httpConnector = server.getConnectors().find {
            !it.connectionFactories.any {
              it instanceof SslConnectionFactory
            }
          }
          HttpConfiguration config = (httpConnector.connectionFactories.find {
            it instanceof HttpConnectionFactory
          }
          as HttpConnectionFactory).getHttpConfiguration()
          httpConnector.addConnectionFactory(new HTTP2CServerConnectionFactory(config))
        }
      }
    }
    handlers {
      all {
        response
        .status(200)
        .addHeader("x-amzn-RequestId", servedRequestId.get())
        .sendWithType("application/x-amz-json-1.1", responseBody.get())
      }
    }
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // the actual service returns cbor encoded json
    System.setProperty("aws.cborEnabled", "false")
  }

  @Override
  String operation() {
    null
  }

  @Override
  String service() {
    null
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    true
  }

  @Override
  protected long dataStreamsBucketDuration() {
    TimeUnit.MILLISECONDS.toNanos(250)
  }

  abstract String expectedOperation(String awsService, String awsOperation)

  abstract String expectedService(String awsService, String awsOperation)

  def watch(builder, callback) {
    builder.addExecutionInterceptor(new ExecutionInterceptor() {
      @Override
      void afterExecution(Context.AfterExecution context, ExecutionAttributes executionAttributes) {
        callback.call()
      }
    })
  }

  def "send #operation request with builder #builder.class.getSimpleName() mocked response"() {
    setup:
    def conditions = new PollingConditions(timeout: 1)
    boolean executed = false
    def client = builder
    // tests that our instrumentation doesn't disturb any overridden configuration
    .overrideConfiguration({
      watch(it, {
        executed = true
      })
    })
    .endpointOverride(server.address)
    .region(Region.AP_NORTHEAST_1)
    .credentialsProvider(CREDENTIALS_PROVIDER)
    .build()
    responseBody.set(body)
    servedRequestId.set(requestId)
    when:
    def response = call.call(client)

    if (response instanceof Future) {
      response = response.get()
    }
    TEST_WRITER.waitForTraces(1)
    TEST_DATA_STREAMS_WRITER.waitForGroups(1)

    then:
    executed
    response != null
    response.class.simpleName.startsWith(operation) || response instanceof ResponseInputStream

    and:
    conditions.eventually {
      List<StatsGroup> results = TEST_DATA_STREAMS_WRITER.groups.findAll {
        it.parentHash == 0
      }
      assert results.size() >= 1
      def pathwayLatencyCount = 0
      def edgeLatencyCount = 0
      results.each {
        group ->
        pathwayLatencyCount += group.pathwayLatency.count
        edgeLatencyCount += group.edgeLatency.count
        verifyAll(group) {
          tags.hasAllTags("direction:" + dsmDirection, "topic:arnprefix:stream/somestream", "type:kinesis")
        }
      }
      verifyAll {
        pathwayLatencyCount == dsmStatCount
        edgeLatencyCount == dsmStatCount
      }
    }

    and:
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
            def checkPeerService = false
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_URL" "${server.address}${path}"
            "$Tags.HTTP_METHOD" "$method"
            "$Tags.HTTP_STATUS" 200
            "aws.service" "$service"
            "aws_service" "$service"
            "aws.operation" "${operation}"
            "aws.agent" "java-aws-sdk"
            "aws.requestId" "$requestId"
            "aws.stream.name" "somestream"
            "streamname" "somestream"
            "$DDTags.PATHWAY_HASH" {
              String
            }
            peerServiceFrom("aws.stream.name")
            checkPeerService = true
            defaultTags(false, checkPeerService)
          }
        }
      }
    }

    cleanup:
    servedRequestId.set(null)

    where:
    service   | operation    | dsmDirection | dsmStatCount | method | path | requestId                              | builder                 | call                                                                                                                                                                                                                                                                                            | body
    "Kinesis" | "GetRecords" | "in"         | 1            | "POST" | "/"  | "7a62c49f-347e-4fc4-9331-6e8e7a96aa73" | KinesisClient.builder() | {
      KinesisClient c -> c.getRecords(GetRecordsRequest.builder().streamARN("arnprefix:stream/somestream").build())
    }                                                                                                                                                                               | """{
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
    "Kinesis" | "GetRecords" | "in"         | 2            | "POST" | "/"  | "7a62c49f-347e-4fc4-9331-6e8e7a96aa73" | KinesisClient.builder() | {
      KinesisClient c -> c.getRecords(GetRecordsRequest.builder().streamARN("arnprefix:stream/somestream").build())
    }                                                                                                                                                                               | """{
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
    "Kinesis" | "PutRecord"  | "out"        | 1            | "POST" | "/"  | "UNKNOWN"                              | KinesisClient.builder() | {
      KinesisClient c -> c.putRecord(PutRecordRequest.builder().streamARN("arnprefix:stream/somestream").data(SdkBytes.fromUtf8String("message")).build())
    }                                                                                                                                        | ""
    "Kinesis" | "PutRecords" | "out"        | 1            | "POST" | "/"  | "UNKNOWN"                              | KinesisClient.builder() | {
      KinesisClient c -> c.putRecords(PutRecordsRequest.builder().streamARN("arnprefix:stream/somestream").records(PutRecordsRequestEntry.builder().data(SdkBytes.fromUtf8String("message")).build()).build())
    }                                                                                    | ""
    "Kinesis" | "PutRecords" | "out"        | 2            | "POST" | "/"  | "UNKNOWN"                              | KinesisClient.builder() | {
      KinesisClient c -> c.putRecords(PutRecordsRequest.builder().streamARN("arnprefix:stream/somestream").records(PutRecordsRequestEntry.builder().data(SdkBytes.fromUtf8String("message")).build(), PutRecordsRequestEntry.builder().data(SdkBytes.fromUtf8String("message")).build()).build())
    } | ""
  }

  def "send #operation async request with builder #builder.class.getSimpleName() mocked response"() {
    setup:
    def conditions = new PollingConditions(timeout: 1)
    boolean executed = false
    // KinesisAsyncClient defaults to HTTP/2 with ALPN negotiation, which doesn't work with the
    // test server's HTTP2CServerConnectionFactory (h2c). Force HTTP/1.1 for compatibility.
    // Note: AWS SDK 2.25+ supports h2c via .protocol(Protocol.HTTP2).protocolNegotiation(ProtocolNegotiation.ASSUME_PROTOCOL)
    def httpClient = NettyNioAsyncHttpClient.builder()
    .protocol(Protocol.HTTP1_1)
    .build()
    def client = builder
    // tests that our instrumentation doesn't disturb any overridden configuration
    .overrideConfiguration({
      watch(it, {
        executed = true
      })
    })
    .httpClient(httpClient)
    .endpointOverride(server.address)
    .region(Region.AP_NORTHEAST_1)
    .credentialsProvider(CREDENTIALS_PROVIDER)
    .build()
    responseBody.set(body)
    servedRequestId.set(requestId)
    when:
    def response = call.call(client)

    if (response instanceof Future) {
      response = response.get()
    }
    TEST_WRITER.waitForTraces(1)
    TEST_DATA_STREAMS_WRITER.waitForGroups(1)

    then:
    executed
    response != null

    and:
    conditions.eventually {
      List<StatsGroup> results = TEST_DATA_STREAMS_WRITER.groups.findAll {
        it.parentHash == 0
      }
      assert results.size() >= 1
      def pathwayLatencyCount = 0
      def edgeLatencyCount = 0
      results.each {
        group ->
        pathwayLatencyCount += group.pathwayLatency.count
        edgeLatencyCount += group.edgeLatency.count
        verifyAll(group) {
          tags.hasAllTags("direction:" + dsmDirection, "topic:arnprefix:stream/somestream", "type:kinesis")
        }
      }
      verifyAll {
        pathwayLatencyCount == dsmStatCount
        edgeLatencyCount == dsmStatCount
      }
    }

    and:
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
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_URL" "${server.address}${path}"
            "$Tags.HTTP_METHOD" "$method"
            "$Tags.HTTP_STATUS" 200
            "aws.service" "$service"
            "aws_service" "$service"
            "aws.operation" "${operation}"
            "aws.agent" "java-aws-sdk"
            "aws.requestId" "$requestId"
            "aws.stream.name" "somestream"
            "streamname" "somestream"
            "$DDTags.PATHWAY_HASH" {
              String
            }
            peerServiceFrom("aws.stream.name")
            defaultTags(false, true)
          }
        }
      }
    }

    cleanup:
    servedRequestId.set(null)

    where:
    service   | operation    | dsmDirection | dsmStatCount | method | path | requestId                              | builder                      | call                                                                                                                                                                                                                                                                                                 | body
    "Kinesis" | "GetRecords" | "in"         | 1            | "POST" | "/"  | "7a62c49f-347e-4fc4-9331-6e8e7a96aa73" | KinesisAsyncClient.builder() | {
      KinesisAsyncClient c -> c.getRecords(GetRecordsRequest.builder().streamARN("arnprefix:stream/somestream").build())
    }                                                                                                                                                                               | """{
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
    "Kinesis" | "GetRecords" | "in"         | 2            | "POST" | "/"  | "7a62c49f-347e-4fc4-9331-6e8e7a96aa73" | KinesisAsyncClient.builder() | {
      KinesisAsyncClient c -> c.getRecords(GetRecordsRequest.builder().streamARN("arnprefix:stream/somestream").build())
    }                                                                                                                                                                               | """{
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
    "Kinesis" | "PutRecord"  | "out"        | 1            | "POST" | "/"  | "UNKNOWN"                              | KinesisAsyncClient.builder() | {
      KinesisAsyncClient c -> c.putRecord(PutRecordRequest.builder().streamARN("arnprefix:stream/somestream").data(SdkBytes.fromUtf8String("message")).build())
    }                                                                                                                                        | ""
    "Kinesis" | "PutRecords" | "out"        | 1            | "POST" | "/"  | "UNKNOWN"                              | KinesisAsyncClient.builder() | {
      KinesisAsyncClient c -> c.putRecords(PutRecordsRequest.builder().streamARN("arnprefix:stream/somestream").records(PutRecordsRequestEntry.builder().data(SdkBytes.fromUtf8String("message")).build()).build())
    }                                                                                    | ""
    "Kinesis" | "PutRecords" | "out"        | 2            | "POST" | "/"  | "UNKNOWN"                              | KinesisAsyncClient.builder() | {
      KinesisAsyncClient c -> c.putRecords(PutRecordsRequest.builder().streamARN("arnprefix:stream/somestream").records(PutRecordsRequestEntry.builder().data(SdkBytes.fromUtf8String("message")).build(), PutRecordsRequestEntry.builder().data(SdkBytes.fromUtf8String("message")).build()).build())
    } | ""
  }
}

class Aws2KinesisDataStreamsV0Test extends Aws2KinesisDataStreamsTest {

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

class Aws2KinesisDataStreamsV1ForkedTest extends Aws2KinesisDataStreamsTest {

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
