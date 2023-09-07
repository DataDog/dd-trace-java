import datadog.trace.api.DDTags
import datadog.trace.core.datastreams.StatsGroup
import spock.lang.IgnoreIf

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

import com.amazon.sqs.javamessaging.ProviderConfiguration
import com.amazon.sqs.javamessaging.SQSConnectionFactory
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.naming.SpanNaming
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import spock.lang.Shared

import javax.jms.Session

abstract class SqsClientTest extends VersionedNamingTestBase {

  def setup() {
    System.setProperty(SdkSystemSetting.AWS_ACCESS_KEY_ID.property(), "my-access-key")
    System.setProperty(SdkSystemSetting.AWS_SECRET_ACCESS_KEY.property(), "my-secret-key")
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // Set a service name that gets sorted early with SORT_BY_NAMES
    injectSysConfig(GeneralConfig.SERVICE_NAME, "A-service")
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, isDataStreamsEnabled().toString())
  }

  @Shared
  def credentialsProvider = AnonymousCredentialsProvider.create()
  @Shared
  def server = SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start()
  @Shared
  def address = server.waitUntilStarted().localAddress()
  @Shared
  def endpoint = URI.create("http://localhost:${address.port}")

  def cleanupSpec() {
    if (server != null) {
      server.stopAndWait()
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

  boolean hasTimeInQueueSpan() {
    false
  }

  abstract String expectedOperation(String awsService, String awsOperation)

  abstract String expectedService(String awsService, String awsOperation)

  def "trace details propagated via SQS system message attributes"() {
    setup:
    def client = SqsClient.builder()
      .region(Region.EU_CENTRAL_1)
      .endpointOverride(endpoint)
      .credentialsProvider(credentialsProvider)
      .build()
    def queueUrl = client.createQueue(CreateQueueRequest.builder().queueName('somequeue').build()).queueUrl()
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody('sometext').build())
    })
    def messages = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl).build()).messages()

    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    messages.forEach {/* consume to create message spans */ }

    then:
    def sendSpan
    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName expectedService("Sqs", "SendMessage")
          operationName expectedOperation("Sqs", "SendMessage")
          resourceName "Sqs.SendMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "SendMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            "aws.requestId" "00000000-0000-0000-0000-000000000000"
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags()
          }
        }
        sendSpan = span(1)
      }
      trace(1) {
        span {
          serviceName expectedService("Sqs", "ReceiveMessage")
          operationName expectedOperation("Sqs", "ReceiveMessage")
          resourceName "Sqs.ReceiveMessage"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false
          measured true
          childOf(sendSpan)
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "ReceiveMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            "aws.requestId" "00000000-0000-0000-0000-000000000000"
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }

      verifyAll(first) {
        edgeTags == ["direction:out", "topic:somequeue", "type:sqs"]
        edgeTags.size() == 3
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        edgeTags == ["direction:in", "topic:somequeue", "type:sqs"]
        edgeTags.size() == 3
      }
    }

    assert messages[0].attributesAsStrings()['AWSTraceHeader'] =~
    /Root=1-[0-9a-f]{8}-00000000${sendSpan.traceId.toHexStringPadded(16)};Parent=${DDSpanId.toHexStringPadded(sendSpan.spanId)};Sampled=1/

    cleanup:
    client.close()
  }

  @IgnoreIf({instance.isDataStreamsEnabled()})
  def "trace details propagated from SQS to JMS"() {
    setup:
    def client = SqsClient.builder()
      .region(Region.EU_CENTRAL_1)
      .endpointOverride(endpoint)
      .credentialsProvider(credentialsProvider)
      .build()

    def connectionFactory = new SQSConnectionFactory(new ProviderConfiguration(), client)
    def connection = connectionFactory.createConnection()
    def session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    def queue = session.createQueue('somequeue')
    def consumer = session.createConsumer(queue)

    TEST_WRITER.clear()

    when:
    connection.start()
    TraceUtils.runUnderTrace('parent', {
      client.sendMessage(SendMessageRequest.builder().queueUrl(queue.queueUrl).messageBody('sometext').build())
    })
    def message = consumer.receive()
    consumer.receiveNoWait()

    then:
    def sendSpan
    def timeInQueue = hasTimeInQueueSpan()

    assertTraces(4, SORT_TRACES_BY_START) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName expectedService("Sqs", "SendMessage")
          operationName expectedOperation("Sqs", "SendMessage")
          resourceName "Sqs.SendMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "SendMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            "aws.requestId" "00000000-0000-0000-0000-000000000000"
            defaultTags()
          }
        }
        sendSpan = span(1)
      }
      trace(timeInQueue ? 2 : 1) {
        span {
          serviceName expectedService("Sqs", "ReceiveMessage")
          operationName expectedOperation("Sqs", "ReceiveMessage")
          resourceName "Sqs.ReceiveMessage"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false
          measured true
          childOf(timeInQueue ? span(1): sendSpan)
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "ReceiveMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            "aws.requestId" "00000000-0000-0000-0000-000000000000"
            defaultTags(!timeInQueue)
          }
        }
        if (timeInQueue) {
          // only v1 has this automatically without legacy disabled
          span {
            serviceName "sqs-queue"
            operationName "aws.sqs.deliver"
            resourceName "Sqs.DeliverMessage"
            spanType DDSpanTypes.MESSAGE_BROKER
            errored false
            measured true
            childOf(sendSpan)
            tags {
              "$Tags.COMPONENT" "java-aws-sdk"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_BROKER
              "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
              "aws.requestId" "00000000-0000-0000-0000-000000000000"
              defaultTags(true)
            }
          }
        }
      }
      trace(1) {
        span {
          serviceName expectedService("Sqs", "DeleteMessage")
          operationName expectedOperation("Sqs", "DeleteMessage")
          resourceName "Sqs.DeleteMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "DeleteMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            "aws.requestId" "00000000-0000-0000-0000-000000000000"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName SpanNaming.instance().namingSchema().messaging().inboundService(Config.get().getServiceName(), "jms")
          operationName SpanNaming.instance().namingSchema().messaging().inboundOperation("jms")
          resourceName "Consumed from Queue somequeue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false
          measured true
          childOf(sendSpan)
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
    }

    def expectedTraceProperty = 'X-Amzn-Trace-Id'.toLowerCase(Locale.ENGLISH).replace('-', '__dash__')
    assert message.getStringProperty(expectedTraceProperty) =~
    /Root=1-[0-9a-f]{8}-00000000${sendSpan.traceId.toHexStringPadded(16)};Parent=${DDSpanId.toHexStringPadded(sendSpan.spanId)};Sampled=1/

    cleanup:
    session.close()
    connection.stop()
    client.close()
  }
}

class SqsClientV0Test extends SqsClientTest {

  @Override
  String expectedOperation(String awsService, String awsOperation) {
    "aws.http"
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    if ("Sqs" == awsService) {
      return "sqs"
    }
    return "java-aws-sdk"
  }

  @Override
  int version() {
    0
  }
}

class SqsClientV1ForkedTest extends SqsClientTest {

  @Override
  String expectedOperation(String awsService, String awsOperation) {
    if (awsService == "Sqs") {
      if (awsOperation == "ReceiveMessage") {
        return "aws.sqs.process"
      } else if (awsOperation == "SendMessage") {
        return "aws.sqs.send"
      }
    }
    return "aws.${awsService.toLowerCase()}.request"
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    "A-service"
  }

  @Override
  int version() {
    1
  }
}

class SqsClientV0DataStreamsTest extends SqsClientTest {
  @Override
  String expectedOperation(String awsService, String awsOperation) {
    "aws.http"
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    if ("Sqs" == awsService) {
      return "sqs"
    }
    return "java-aws-sdk"
  }

  @Override
  boolean isDataStreamsEnabled() {
    true
  }


  @Override
  int version() {
    0
  }
}

class SqsClientV1DataStreamsForkedTest extends SqsClientTest {
  @Override
  String expectedOperation(String awsService, String awsOperation) {
    if (awsService == "Sqs") {
      if (awsOperation == "ReceiveMessage") {
        return "aws.sqs.process"
      } else if (awsOperation == "SendMessage") {
        return "aws.sqs.send"
      }
    }
    return "aws.${awsService.toLowerCase()}.request"
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    "A-service"
  }

  @Override
  boolean isDataStreamsEnabled() {
    true
  }

  @Override
  int version() {
    1
  }
}


