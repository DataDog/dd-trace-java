import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.instrumentation.aws.ExpectedQueryParams
import datadog.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory
import datadog.eclipse.jetty.server.HttpConfiguration
import datadog.eclipse.jetty.server.HttpConnectionFactory
import datadog.eclipse.jetty.server.Server
import datadog.eclipse.jetty.server.ServerConnector
import datadog.eclipse.jetty.server.SslConnectionFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.interceptor.Context
import software.amazon.awssdk.core.interceptor.ExecutionAttributes
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry
import software.amazon.awssdk.services.sns.model.PublishRequest
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

abstract class Aws2SnsDataStreamsTest extends VersionedNamingTestBase {

  private static final StaticCredentialsProvider CREDENTIALS_PROVIDER = StaticCredentialsProvider
  .create(AwsBasicCredentials.create("my-access-key", "my-secret-key"))

  @Shared
  def responseBody = new AtomicReference<String>()
  @Shared
  def servedRequestId = new AtomicReference<String>()

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

  @Unroll
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
          tags.hasAllTags("direction:" + dsmDirection, "topic:mytopic", "type:sns")
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
            "$Tags.HTTP_METHOD" "$method"
            "$Tags.HTTP_STATUS" 200
            "aws.service" "$service"
            "aws_service" "$service"
            "aws.operation" "${operation}"
            "aws.agent" "java-aws-sdk"
            "aws.requestId" "$requestId"
            "aws.topic.name" "mytopic"
            "topicname" "mytopic"
            "$DDTags.PATHWAY_HASH" {
              String
            }
            checkPeerService = true
            urlTags("${server.address}${path}", ExpectedQueryParams.getExpectedQueryParams(operation))
            defaultTags(false, checkPeerService)
          }
        }
      }
    }

    cleanup:
    servedRequestId.set(null)

    where:
    service | operation      | dsmDirection | dsmStatCount | method | path | requestId                              | builder             | call                                                                                                                                                                                                                                                                        | body
    "Sns"   | "Publish"      | "out"        | 1            | "POST" | "/"  | "7a62c49f-347e-4fc4-9331-6e8e7a96aa73" | SnsClient.builder() | {
      SnsClient c -> c.publish(PublishRequest.builder().topicArn("arnprefix:mytopic").message("hello").build())
    }                                                                                                                                                               | """<?xml version="1.0" encoding="UTF-8" ?><MessageId>f2edefec-298a-58d7-bcc0-b1bd2077fccb</MessageId>"""
    "Sns"   | "PublishBatch" | "out"        | 2            | "POST" | "/"  | "7a62c49f-347e-4fc4-9331-6e8e7a96aa73" | SnsClient.builder() | {
      SnsClient c -> c.publishBatch(PublishBatchRequest.builder().topicArn("arnprefix:mytopic").publishBatchRequestEntries(PublishBatchRequestEntry.builder().id("1").message("hello").build(), PublishBatchRequestEntry.builder().id("2").message("world").build()).build())
    } | """<?xml version="1.0" encoding="UTF-8" ?>
  <Successful>
    <Id>1</Id>
    <MessageId>4898a3df-db3a-5078-a6a9-fd895f9acb64</MessageId>
  </Successful>
  <Successful>
    <Id>2</Id>
    <MessageId>0967c76c-5cbb-5637-82f8-993ad81bed2b</MessageId>
  </Successful>
  <Failed/>"""
  }

  def "send #operation async request with builder #builder.class.getSimpleName() mocked response"() {
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
          tags.hasAllTags("direction:" + dsmDirection, "topic:mytopic", "type:sns")
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
            "$Tags.HTTP_METHOD" "$method"
            "$Tags.HTTP_STATUS" 200
            "aws.service" "$service"
            "aws_service" "$service"
            "aws.operation" "${operation}"
            "aws.agent" "java-aws-sdk"
            "aws.requestId" "$requestId"
            "aws.topic.name" "mytopic"
            "topicname" "mytopic"
            "$DDTags.PATHWAY_HASH" {
              String
            }
            urlTags("${server.address}${path}", ExpectedQueryParams.getExpectedQueryParams(operation))
            defaultTags(false, true)
          }
        }
      }
    }

    cleanup:
    servedRequestId.set(null)

    where:
    service | operation      | dsmDirection | dsmStatCount | method | path | requestId                              | builder                  | call                                                                                                                                                                                                                                                                             | body
    "Sns"   | "Publish"      | "out"        | 1            | "POST" | "/"  | "7a62c49f-347e-4fc4-9331-6e8e7a96aa73" | SnsAsyncClient.builder() | {
      SnsAsyncClient c -> c.publish(PublishRequest.builder().topicArn("arnprefix:mytopic").message("hello").build())
    }                                                                                                                                                               | """<?xml version="1.0" encoding="UTF-8" ?><MessageId>f2edefec-298a-58d7-bcc0-b1bd2077fccb</MessageId>"""
    "Sns"   | "PublishBatch" | "out"        | 2            | "POST" | "/"  | "7a62c49f-347e-4fc4-9331-6e8e7a96aa73" | SnsAsyncClient.builder() | {
      SnsAsyncClient c -> c.publishBatch(PublishBatchRequest.builder().topicArn("arnprefix:mytopic").publishBatchRequestEntries(PublishBatchRequestEntry.builder().id("1").message("hello").build(), PublishBatchRequestEntry.builder().id("2").message("world").build()).build())
    } | """<?xml version="1.0" encoding="UTF-8" ?>
  <Successful>
    <Id>1</Id>
    <MessageId>4898a3df-db3a-5078-a6a9-fd895f9acb64</MessageId>
  </Successful>
  <Successful>
    <Id>2</Id>
    <MessageId>0967c76c-5cbb-5637-82f8-993ad81bed2b</MessageId>
  </Successful>
  <Failed/>"""
  }
}

class Aws2SnsDataStreamsV0Test extends Aws2SnsDataStreamsTest {

  @Override
  String expectedOperation(String awsService, String awsOperation) {
    "aws.http"
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    "sns"
  }

  @Override
  int version() {
    0
  }
}

class Aws2SnsDataStreamsV1ForkedTest extends Aws2SnsDataStreamsTest {

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
