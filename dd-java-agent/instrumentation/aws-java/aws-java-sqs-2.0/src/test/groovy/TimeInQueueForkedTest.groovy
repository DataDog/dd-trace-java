import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.aws.ExpectedQueryParams
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

class TimeInQueueForkedTest extends InstrumentationSpecification {

  def setup() {
    System.setProperty(SdkSystemSetting.AWS_ACCESS_KEY_ID.property(), "my-access-key")
    System.setProperty(SdkSystemSetting.AWS_SECRET_ACCESS_KEY.property(), "my-secret-key")
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("sqs.legacy.tracing.enabled", 'false')
    // Set a service name that gets sorted early with SORT_BY_NAMES
    injectSysConfig(GeneralConfig.SERVICE_NAME, "A-service")
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
      try {
        server.stopAndWait()
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt()
      }
    }
  }

  def sentMessages = [
    "a message",
    "another message",
    "yet another message",
    "another message again",
    "just one more message"
  ]

  def "sync messages without SentTimestamps have no time-in-queue span"() {
    setup:
    def client = SqsClient.builder()
      .region(Region.EU_CENTRAL_1)
      .endpointOverride(endpoint)
      .credentialsProvider(credentialsProvider)
      .build()
    def queueUrl = client.createQueue(
      CreateQueueRequest.builder().queueName('somequeue').build()
      ).queueUrl()
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessageBatch(SendMessageBatchRequest.builder()
        .queueUrl(queueUrl)
        .entries(
        sentMessages.withIndex().collect {
          SendMessageBatchRequestEntry.builder()
            .messageBody(it.first)
            .id(it.second as String)
            .build()
        }
        ).build())
    })
    def messages = client.receiveMessage(
      ReceiveMessageRequest.builder()
      .queueUrl(queueUrl)
      .maxNumberOfMessages(10)
      .build()
      ).messages().collect {
        it.body()
      }

    then:
    messages.sort() == sentMessages.sort()
    assertSqsTrace()

    cleanup:
    client.close()
  }

  def "async messages without SentTimestamps have no time-in-queue span"() {
    setup:
    def client = SqsAsyncClient.builder()
      .region(Region.EU_CENTRAL_1)
      .endpointOverride(endpoint)
      .credentialsProvider(credentialsProvider)
      .build()
    def queueUrl = client.createQueue(
      CreateQueueRequest.builder().queueName('somequeue').build()
      ).join().queueUrl()
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessageBatch(SendMessageBatchRequest.builder()
        .queueUrl(queueUrl)
        .entries(
        sentMessages.withIndex().collect {
          SendMessageBatchRequestEntry.builder()
            .messageBody(it.first)
            .id(it.second as String)
            .build()
        }
        ).build()).join()
    })
    def messages = client.receiveMessage(
      ReceiveMessageRequest.builder()
      .queueUrl(queueUrl)
      .maxNumberOfMessages(10)
      .build()
      ).join().messages().collect {
        it.body()
      }

    then:
    messages.sort() == sentMessages.sort()
    assertSqsTrace()

    cleanup:
    client.close()
  }

  def "sync messages with SentTimestamps have time-in-queue span"() {
    setup:
    def client = SqsClient.builder()
      .region(Region.EU_CENTRAL_1)
      .endpointOverride(endpoint)
      .credentialsProvider(credentialsProvider)
      .build()
    def queueUrl = client.createQueue(
      CreateQueueRequest.builder().queueName('somequeue').build()
      ).queueUrl()
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessageBatch(SendMessageBatchRequest.builder()
        .queueUrl(queueUrl)
        .entries(
        sentMessages.withIndex().collect {
          SendMessageBatchRequestEntry.builder()
            .messageBody(it.first)
            .id(it.second as String)
            .build()
        }
        ).build())
    })
    def messages = client.receiveMessage(
      ReceiveMessageRequest.builder()
      .queueUrl(queueUrl)
      .attributeNamesWithStrings("SentTimestamp")
      .maxNumberOfMessages(10)
      .build()
      ).messages().collect {
        it.body()
      }

    then:
    messages.sort() == sentMessages.sort()
    assertSqsTraceWithTimeInQueue()

    cleanup:
    client.close()
  }

  def "async messages with SentTimestamps have time-in-queue span"() {
    setup:
    def client = SqsAsyncClient.builder()
      .region(Region.EU_CENTRAL_1)
      .endpointOverride(endpoint)
      .credentialsProvider(credentialsProvider)
      .build()
    def queueUrl = client.createQueue(
      CreateQueueRequest.builder().queueName('somequeue').build()
      ).join().queueUrl()
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessageBatch(SendMessageBatchRequest.builder()
        .queueUrl(queueUrl)
        .entries(
        sentMessages.withIndex().collect {
          SendMessageBatchRequestEntry.builder()
            .messageBody(it.first)
            .id(it.second as String)
            .build()
        }
        ).build()).join()
    })
    def messages = client.receiveMessage(
      ReceiveMessageRequest.builder()
      .queueUrl(queueUrl)
      .attributeNamesWithStrings("SentTimestamp")
      .maxNumberOfMessages(10)
      .build()
      ).join().messages().collect {
        it.body()
      }

    then:
    messages.sort() == sentMessages.sort()
    assertSqsTraceWithTimeInQueue()

    cleanup:
    client.close()
  }

  private void assertSqsTrace() {
    def sendSpan
    assertTraces(6) {
      trace(2) {
        basicSpan(it, "parent")
        producerSpan(it, span(0))
        sendSpan = span(1)
      }
      trace(1) {
        consumerSpan(it, sendSpan)
      }
      trace(1) {
        consumerSpan(it, sendSpan)
      }
      trace(1) {
        consumerSpan(it, sendSpan)
      }
      trace(1) {
        consumerSpan(it, sendSpan)
      }
      trace(1) {
        consumerSpan(it, sendSpan)
      }
    }
  }

  private void assertSqsTraceWithTimeInQueue() {
    def sendSpan, queueSpan
    assertTraces(6) {
      trace(2) {
        basicSpan(it, "parent")
        producerSpan(it, span(0))
        sendSpan = span(1)
      }
      trace(2) {
        consumerSpan(it, span(1))
        timeInQueueSpan(it, sendSpan)
        queueSpan = span(1)
      }
      trace(1) {
        consumerSpan(it, queueSpan)
      }
      trace(1) {
        consumerSpan(it, queueSpan)
      }
      trace(1) {
        consumerSpan(it, queueSpan)
      }
      trace(1) {
        consumerSpan(it, queueSpan)
      }
    }
  }

  def producerSpan(TraceAssert traceAssert, parent) {
    traceAssert.span {
      serviceName "A-service"
      operationName "aws.http"
      resourceName "Sqs.SendMessageBatch"
      spanType DDSpanTypes.HTTP_CLIENT
      errored false
      measured true
      childOf(parent)
      tags {
        "$Tags.COMPONENT" "java-aws-sdk"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.HTTP_METHOD" "POST"
        "$Tags.HTTP_STATUS" 200
        "$Tags.PEER_PORT" address.port
        "$Tags.PEER_HOSTNAME" "localhost"
        "aws.service" "Sqs"
        "aws_service" "Sqs"
        "aws.operation" "SendMessageBatch"
        "aws.agent" "java-aws-sdk"
        "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
        "aws.requestId" { it.trim() == "00000000-0000-0000-0000-000000000000" } // the test server seem messing with request id and insert \n
        urlTags("http://localhost:${address.port}/", ExpectedQueryParams.getExpectedQueryParams("SendMessageBatch"))
        defaultTags()
      }
    }
  }

  def consumerSpan(TraceAssert traceAssert, parent) {
    traceAssert.span {
      serviceName "A-service"
      operationName "aws.http"
      resourceName "Sqs.ReceiveMessage"
      spanType DDSpanTypes.MESSAGE_CONSUMER
      errored false
      measured true
      childOf(parent)
      tags {
        "$Tags.COMPONENT" "java-aws-sdk"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        "aws.service" "Sqs"
        "aws_service" "Sqs"
        "aws.operation" "ReceiveMessage"
        "aws.agent" "java-aws-sdk"
        "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
        "aws.requestId" { it.trim() == "00000000-0000-0000-0000-000000000000" } // the test server seem messing with request id and insert \n
        defaultTags(parent.resourceName as String == "Sqs.SendMessageBatch")
      }
    }
  }

  def timeInQueueSpan(TraceAssert traceAssert, parent) {
    traceAssert.span {
      serviceName "sqs"
      operationName "aws.http"
      resourceName "Sqs.DeliverMessage"
      spanType DDSpanTypes.MESSAGE_BROKER
      errored false
      measured true
      childOf(parent)
      tags {
        "$Tags.COMPONENT" "java-aws-sdk"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_BROKER
        "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
        "aws.requestId" { it.trim() == "00000000-0000-0000-0000-000000000000" } // the test server seem messing with request id and insert \n
        defaultTags(true)
      }
    }
  }
}
