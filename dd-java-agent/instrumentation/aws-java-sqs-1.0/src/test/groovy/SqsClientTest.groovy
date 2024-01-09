import com.amazon.sqs.javamessaging.ProviderConfiguration
import com.amazon.sqs.javamessaging.SQSConnectionFactory
import com.amazonaws.SDKGlobalConfiguration
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.MessageAttributeValue
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.naming.SpanNaming
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.instrumentation.aws.v1.sqs.TracingList
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import spock.lang.IgnoreIf
import spock.lang.Shared

import javax.jms.Session

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static java.nio.charset.StandardCharsets.UTF_8

abstract class SqsClientTest extends VersionedNamingTestBase {

  def setup() {
    System.setProperty(SDKGlobalConfiguration.ACCESS_KEY_SYSTEM_PROPERTY, "my-access-key")
    System.setProperty(SDKGlobalConfiguration.SECRET_KEY_SYSTEM_PROPERTY, "my-secret-key")
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // Set a service name that gets sorted early with SORT_BY_NAMES
    injectSysConfig(GeneralConfig.SERVICE_NAME, "A-service")
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, isDataStreamsEnabled().toString())
  }

  @Shared
  def credentialsProvider = new AWSStaticCredentialsProvider(new AnonymousAWSCredentials())
  @Shared
  def server = SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start()
  @Shared
  def address = server.waitUntilStarted().localAddress()
  @Shared
  def endpoint = new AwsClientBuilder.EndpointConfiguration("http://localhost:${address.port}", "elasticmq")

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

  def cleanupSpec() {
    if (server != null) {
      server.stopAndWait()
    }
  }

  def "trace details propagated via SQS system message attributes"() {
    setup:
    def client = AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(endpoint)
      .withCredentials(credentialsProvider)
      .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessage(queueUrl, 'sometext')
    })
    def messages = client.receiveMessage(queueUrl).messages
    messages.forEach {/* consume to create message spans */ }

    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    then:
    def sendSpan
    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName expectedService("SQS", "SendMessage")
          operationName expectedOperation("SQS", "SendMessage")
          resourceName "SQS.SendMessage"
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
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.endpoint" "http://localhost:${address.port}"
            "aws.operation" "SendMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
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
          serviceName expectedService("SQS", "ReceiveMessage")
          operationName expectedOperation("SQS", "ReceiveMessage")
          resourceName "SQS.ReceiveMessage"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false
          measured true
          childOf(sendSpan)
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.operation" "ReceiveMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
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

    assert messages[0].attributes['AWSTraceHeader'] =~
    /Root=1-[0-9a-f]{8}-00000000${sendSpan.traceId.toHexStringPadded(16)};Parent=${DDSpanId.toHexStringPadded(sendSpan.spanId)};Sampled=1/

    cleanup:
    client.shutdown()
  }

  @IgnoreIf({ instance.isDataStreamsEnabled() })
  def "trace details propagated via embedded SQS message attribute (string)"() {
    setup:
    TEST_WRITER.clear()

    when:
    def message = new Message()
    message.addMessageAttributesEntry('_datadog', new MessageAttributeValue().withDataType('String').withStringValue(
      "{\"x-datadog-trace-id\": \"4948377316357291421\", \"x-datadog-parent-id\": \"6746998015037429512\", \"x-datadog-sampling-priority\": \"1\"}"
      ))
    def messages = new TracingList([message], "http://localhost:${address.port}/000000000000/somequeue")

    messages.forEach {/* consume to create message spans */ }

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName expectedService("SQS", "ReceiveMessage")
          operationName expectedOperation("SQS", "ReceiveMessage")
          resourceName "SQS.ReceiveMessage"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false
          measured true
          traceId(4948377316357291421 as BigInteger)
          parentSpanId(6746998015037429512 as BigInteger)
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.operation" "ReceiveMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            defaultTags(true)
          }
        }
      }
    }
  }

  @IgnoreIf({ instance.isDataStreamsEnabled() })
  def "trace details propagated via embedded SQS message attribute (binary)"() {
    setup:
    TEST_WRITER.clear()

    when:
    def message = new Message()
    message.addMessageAttributesEntry('_datadog', new MessageAttributeValue().withDataType('Binary').withBinaryValue(
      UTF_8.encode('eyJ4LWRhdGFkb2ctdHJhY2UtaWQiOiI0OTQ4Mzc3MzE2MzU3MjkxNDIxIiwieC1kYXRhZG9nLXBhcmVudC1pZCI6IjY3NDY5OTgwMTUwMzc0Mjk1MTIiLCJ4LWRhdGFkb2ctc2FtcGxpbmctcHJpb3JpdHkiOiIxIn0=')
      ))
    def messages = new TracingList([message], "http://localhost:${address.port}/000000000000/somequeue")

    messages.forEach {/* consume to create message spans */ }

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName expectedService("SQS", "ReceiveMessage")
          operationName expectedOperation("SQS", "ReceiveMessage")
          resourceName "SQS.ReceiveMessage"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false
          measured true
          traceId(4948377316357291421 as BigInteger)
          parentSpanId(6746998015037429512 as BigInteger)
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.operation" "ReceiveMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            defaultTags(true)
          }
        }
      }
    }
  }

  @IgnoreIf({ instance.isDataStreamsEnabled() })
  def "trace details propagated from SQS to JMS"() {
    setup:
    def client = AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(endpoint)
      .withCredentials(credentialsProvider)
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
      client.sendMessage(queue.queueUrl, 'sometext')
    })
    def message = consumer.receive()
    consumer.receiveNoWait()

    then:
    def sendSpan
    def timeInQueue = hasTimeInQueueSpan()
    // Order has changed in 1.10+ versions of amazon-sqs-java-messaging-lib
    // so sort by names service/operation/resource
    assertTraces(4, SORT_TRACES_BY_START) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName expectedService("SQS", "SendMessage")
          operationName expectedOperation("SQS", "SendMessage")
          resourceName "SQS.SendMessage"
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
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.endpoint" "http://localhost:${address.port}"
            "aws.operation" "SendMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            defaultTags()
          }
        }
        sendSpan = span(1)
      }
      trace(timeInQueue ? 2 : 1) {
        span {
          serviceName expectedService("SQS", "ReceiveMessage")
          operationName expectedOperation("SQS", "ReceiveMessage")
          resourceName "SQS.ReceiveMessage"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false
          measured true
          childOf(timeInQueue ? span(1) : sendSpan)
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.operation" "ReceiveMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            defaultTags(!timeInQueue)
          }
        }
        if (timeInQueue) { // only v1 has this automatically without legacy disabled
          span {
            serviceName "sqs-queue"
            operationName "aws.sqs.deliver"
            resourceName "SQS.DeliverMessage"
            spanType DDSpanTypes.MESSAGE_BROKER
            errored false
            measured true
            childOf(sendSpan)
            tags {
              "$Tags.COMPONENT" "java-aws-sdk"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_BROKER
              "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
              defaultTags(true)
            }
          }
        }
      }
      trace(1) {
        span {
          serviceName expectedService("SQS", "DeleteMessage")
          operationName expectedOperation("SQS", "DeleteMessage")
          resourceName "SQS.DeleteMessage"
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
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.endpoint" "http://localhost:${address.port}"
            "aws.operation" "DeleteMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName SpanNaming.instance().namingSchema().messaging().inboundService("jms", Config.get().isLegacyTracingEnabled(true, "jms")) ?: Config.get().getServiceName()
          operationName SpanNaming.instance().namingSchema().messaging().inboundOperation("jms")
          resourceName "Consumed from Queue somequeue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false
          measured true
          childOf(sendSpan)
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            // This shows up in 1.10+ versions of amazon-sqs-java-messaging-lib
            if (isLatestDepTest) {
              "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            }
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
    client.shutdown()
  }
}

class SqsClientV0Test extends SqsClientTest {

  @Override
  String expectedOperation(String awsService, String awsOperation) {
    "aws.http"
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    if ("SQS" == awsService) {
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
    if (awsService == "SQS") {
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
    if ("SQS" == awsService) {
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
    if (awsService == "SQS") {
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


