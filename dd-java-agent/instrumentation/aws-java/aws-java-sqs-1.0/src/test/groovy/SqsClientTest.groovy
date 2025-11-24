import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static java.nio.charset.StandardCharsets.UTF_8

import com.amazon.sqs.javamessaging.ProviderConfiguration
import com.amazon.sqs.javamessaging.SQSConnectionFactory
import com.amazonaws.SDKGlobalConfiguration
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.MessageAttributeValue
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.amazonaws.services.sqs.model.SendMessageRequest
import com.google.common.collect.ImmutableMap
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.datastreams.DataStreamsTags
import datadog.trace.api.naming.SpanNaming
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.instrumentation.aws.v1.sqs.TracingList
import java.nio.ByteBuffer
import java.nio.charset.Charset
import javax.jms.Session
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import spock.lang.IgnoreIf
import spock.lang.Shared

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
    injectSysConfig("trace.sqs.body.propagation.enabled", "true")
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
      try {
        server.stopAndWait()
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt()
      }
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
        tags.hasAllTags("direction:out", "topic:somequeue", "type:sqs")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags("direction:in", "topic:somequeue", "type:sqs")
      }
    }

    assert messages[0].attributes['AWSTraceHeader'] =~
    /Root=1-[0-9a-f]{8}-00000000${sendSpan.traceId.toHexStringPadded(16)};Parent=${DDSpanId.toHexStringPadded(sendSpan.spanId)};Sampled=1/

    cleanup:
    client.shutdown()
  }

  def "dadatog context is not injected if SqsInjectDatadogAttribute is disabled"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.SQS_INJECT_DATADOG_ATTRIBUTE_ENABLED, "false")
    def client = AmazonSQSClientBuilder.standard()
    .withEndpointConfiguration(endpoint)
    .withCredentials(credentialsProvider)
    .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    client.sendMessage(queueUrl, 'sometext')
    def messages = client.receiveMessage(queueUrl).messages

    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    assert !messages[0].messageAttributes.containsKey("_datadog")

    cleanup:
    client.shutdown()
  }

  @IgnoreIf({ !instance.isDataStreamsEnabled() })
  def "propagation even when message attributes are readonly"() {
    setup:
    def client = AmazonSQSClientBuilder.standard()
    .withEndpointConfiguration(endpoint)
    .withCredentials(credentialsProvider)
    .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      def myAttribute = new MessageAttributeValue()
      myAttribute.setStringValue("hello world")
      myAttribute.setDataType("String")
      def readonlyAttributes = ImmutableMap<String, MessageAttributeValue>.of("my_key", myAttribute)
      def req = new SendMessageRequest(queueUrl, 'sometext')
      req.setMessageAttributes(readonlyAttributes)
      client.sendMessage(req)
    })

    TEST_DATA_STREAMS_WRITER.waitForGroups(1)

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName expectedService("SQS", "SendMessage")
          operationName expectedOperation("SQS", "SendMessage")
          resourceName "SQS.SendMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          childOf(span(0))
        }
      }
    }

    and:
    def recv = new ReceiveMessageRequest(queueUrl)
    recv.withMessageAttributeNames("my_key")
    def messages = client.receiveMessage(recv).messages

    assert messages[0].messageAttributes.containsKey("my_key") // what we set initially
    assert messages[0].messageAttributes.containsKey("_datadog") // what was injected

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
    headerValue
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

    where:
    headerValue << [
      UTF_8.encode('{"x-datadog-trace-id":"4948377316357291421","x-datadog-parent-id":"6746998015037429512","x-datadog-sampling-priority":"1"}'),
      // not sure this test case with base 64 corresponds to an actual use case
      UTF_8.encode('eyJ4LWRhdGFkb2ctdHJhY2UtaWQiOiI0OTQ4Mzc3MzE2MzU3MjkxNDIxIiwieC1kYXRhZG9nLXBhcmVudC1pZCI6IjY3NDY5OTgwMTUwMzc0Mjk1MTIiLCJ4LWRhdGFkb2ctc2FtcGxpbmctcHJpb3JpdHkiOiIxIn0=')
    ]
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
    def ddMsgAttribute = new MessageAttributeValue()
    .withBinaryValue(ByteBuffer.wrap("hello world".getBytes(Charset.defaultCharset())))
    .withDataType("Binary")
    connection.start()
    TraceUtils.runUnderTrace('parent') {
      client.sendMessage(new SendMessageRequest(queue.queueUrl, 'sometext')
      .withMessageAttributes([_datadog: ddMsgAttribute]))
    }
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
          serviceName SpanNaming.instance().namingSchema().messaging().inboundService("jms", Config.get().isJmsLegacyTracingEnabled()).get() ?: Config.get().getServiceName()
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
    assert !message.propertyExists("_datadog")

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
  private static final String MESSAGE_WITH_ATTRIBUTES = "{\n" +
  "  \"Type\" : \"Notification\",\n" +
  "  \"MessageId\" : \"cb337e2a-1c06-5629-86f5-21fba14fb492\",\n" +
  "  \"TopicArn\" : \"arn:aws:sns:us-east-1:223300679234:dsm-dev-sns-topic\",\n" +
  "  \"Message\" : \"Some message\",\n" +
  "  \"Timestamp\" : \"2024-12-10T03:52:41.662Z\",\n" +
  "  \"SignatureVersion\" : \"1\",\n" +
  "  \"Signature\" : \"ZsEewd5gNR8jLC08TenLDp5rhdBtGIdAzWk7j6fzDyUzb/t56R9SBPrNJtjsPO8Ep8v/iGs/wSFUrnm+Zh3N1duc3alR1bKTAbDlzbEBxaHsGcNwzMz14JF7bKLE+3nPIi0/kT8EuIiRevGqPtCG/NEe9oW2dOyvYZvt+L7GC0AS9L0yJp8Ag7NkgNvYbIqPeKcjj8S7WRiV95Useg0P46e5pn5FXmNKPlpIqYN28jnrTZHWUDTiO5RE7lfFcdH2tBaYSR9F/PwA1Mga5NrTxlZp/yDoOlOUFj5zXAtDDpjNTcR48jAu66Mpi1wom7Si7vc3ZsYzN2Z2ig/aUJLaNA==\",\n" +
  "  \"SigningCertURL\" : \"https://sns.us-east-1.amazonaws.com/SimpleNotificationService-some-pem.pem\",\n" +
  "  \"UnsubscribeURL\" : \"https://sns.us-east-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=arn:aws:sns:us-east-1:7270067952343:dsm-dev-sns-topic:0d82adcc-5b42-4035-81c4-22ccd126fc41\",\n" +
  "  \"MessageAttributes\" : {\n" +
  "    \"_datadog\" : {\"Type\":\"Binary\",\"Value\":\"eyJ4LWRhdGFkb2ctdHJhY2UtaWQiOiI1ODExMzQ0MDA5MDA2NDM1Njk0IiwieC1kYXRhZG9nLXBhcmVudC1pZCI6Ijc3MjQzODMxMjg4OTMyNDAxNDAiLCJ4LWRhdGFkb2ctc2FtcGxpbmctcHJpb3JpdHkiOiIwIiwieC1kYXRhZG9nLXRhZ3MiOiJfZGQucC50aWQ9Njc1N2JiMDkwMDAwMDAwMCIsInRyYWNlcGFyZW50IjoiMDAtNjc1N2JiMDkwMDAwMDAwMDUwYTYwYTk2MWM2YzRkNmUtNmIzMjg1ODdiYWIzYjM0Yy0wMCIsInRyYWNlc3RhdGUiOiJkZD1zOjA7cDo2YjMyODU4N2JhYjNiMzRjO3QudGlkOjY3NTdiYjA5MDAwMDAwMDAiLCJkZC1wYXRod2F5LWN0eC1iYXNlNjQiOiJkdzdKcjU0VERkcjA5cFRyOVdUMDlwVHI5V1E9In0=\"}\n" +
  "  }\n" +
  "}"


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

  def "Data streams context extracted from message body"() {
    setup:
    def client = AmazonSQSClientBuilder.standard()
    .withEndpointConfiguration(endpoint)
    .withCredentials(credentialsProvider)
    .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, "false")
    client.sendMessage(queueUrl, MESSAGE_WITH_ATTRIBUTES)
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, "true")
    def messages = client.receiveMessage(queueUrl).messages
    messages.forEach {/* consume to create message spans */ }

    TEST_DATA_STREAMS_WRITER.waitForGroups(1)

    then:
    StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == -2734507826469073289 }

    verifyAll(first) {
      tags.hasAllTags("direction:in", "topic:somequeue", "type:sqs")
    }

    cleanup:
    client.shutdown()
  }

  def "Data streams context not extracted from message body when message attributes are not present"() {
    setup:
    def client = AmazonSQSClientBuilder.standard()
    .withEndpointConfiguration(endpoint)
    .withCredentials(credentialsProvider)
    .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, "false")
    client.sendMessage(queueUrl, '{"Message": "sometext"}')
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, "true")
    def messages = client.receiveMessage(queueUrl).messages
    messages.forEach {}

    TEST_DATA_STREAMS_WRITER.waitForGroups(1)

    then:
    StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }

    verifyAll(first) {
      tags.hasAllTags("direction:in", "topic:somequeue", "type:sqs")
    }

    cleanup:
    client.shutdown()
  }


  def "Data streams context not extracted from message body when message is not a Json"() {
    setup:
    def client = AmazonSQSClientBuilder.standard()
    .withEndpointConfiguration(endpoint)
    .withCredentials(credentialsProvider)
    .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, "false")
    client.sendMessage(queueUrl, '{"Message": "not a json"')
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, "true")
    def messages = client.receiveMessage(queueUrl).messages
    messages.forEach {}

    TEST_DATA_STREAMS_WRITER.waitForGroups(1)

    then:
    StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }

    verifyAll(first) {
      tags.direction == DataStreamsTags.DIRECTION_TAG + ":in"
      tags.topic == DataStreamsTags.TOPIC_TAG + ":somequeue"
      tags.type == DataStreamsTags.TYPE_TAG + ":sqs"
      tags.nonNullSize() == 3
    }

    cleanup:
    client.shutdown()
  }
}


