import com.amazonaws.SDKGlobalConfiguration
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.amazonaws.services.sqs.model.SendMessageBatchRequest
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

class TimeInQueueForkedTest extends InstrumentationSpecification {

  def setup() {
    System.setProperty(SDKGlobalConfiguration.ACCESS_KEY_SYSTEM_PROPERTY, "my-access-key")
    System.setProperty(SDKGlobalConfiguration.SECRET_KEY_SYSTEM_PROPERTY, "my-secret-key")
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("sqs.legacy.tracing.enabled", 'false')
    // Set a service name that gets sorted early with SORT_BY_NAMES
    injectSysConfig(GeneralConfig.SERVICE_NAME, "A-service")
  }

  @Shared
  def credentialsProvider = new AWSStaticCredentialsProvider(new AnonymousAWSCredentials())
  @Shared
  def server = SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start()
  @Shared
  def address = server.waitUntilStarted().localAddress()
  @Shared
  def endpoint = new AwsClientBuilder.EndpointConfiguration("http://localhost:${address.port}", "elasticmq")

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
    def client = AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(endpoint)
      .withCredentials(credentialsProvider)
      .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessageBatch(new SendMessageBatchRequest()
        .withQueueUrl(queueUrl)
        .withEntries(
        sentMessages.withIndex().collect {
          new SendMessageBatchRequestEntry(
            it.second as String, it.first)
        }
        ))
    })
    def messages = client.receiveMessage(
      new ReceiveMessageRequest()
      .withQueueUrl(queueUrl)
      .withMaxNumberOfMessages(10)
      ).messages.collect {
        it.body
      }

    then:
    messages.sort() == sentMessages.sort()
    assertSqsTrace()

    cleanup:
    client.shutdown()
  }

  def "async messages without SentTimestamps have no time-in-queue span"() {
    setup:
    def client = AmazonSQSAsyncClientBuilder.standard()
      .withEndpointConfiguration(endpoint)
      .withCredentials(credentialsProvider)
      .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessageBatch(new SendMessageBatchRequest()
        .withQueueUrl(queueUrl)
        .withEntries(
        sentMessages.withIndex().collect {
          new SendMessageBatchRequestEntry(
            it.second as String, it.first)
        }
        ))
    })
    def messages = client.receiveMessage(
      new ReceiveMessageRequest()
      .withQueueUrl(queueUrl)
      .withMaxNumberOfMessages(10)
      ).messages.collect {
        it.body
      }

    then:
    messages.sort() == sentMessages.sort()
    assertSqsTrace()

    cleanup:
    client.shutdown()
  }

  def "sync messages with SentTimestamps have time-in-queue span"() {
    setup:
    def client = AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(endpoint)
      .withCredentials(credentialsProvider)
      .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessageBatch(new SendMessageBatchRequest()
        .withQueueUrl(queueUrl)
        .withEntries(
        sentMessages.withIndex().collect {
          new SendMessageBatchRequestEntry(
            it.second as String, it.first)
        }
        ))
    })
    def messages = client.receiveMessage(
      new ReceiveMessageRequest()
      .withQueueUrl(queueUrl)
      .withMaxNumberOfMessages(10)
      .withAttributeNames("SentTimestamp")
      ).messages.collect {
        it.body
      }

    then:
    messages.sort() == sentMessages.sort()
    assertSqsTraceWithTimeInQueue()

    cleanup:
    client.shutdown()
  }

  def "async messages with SentTimestamps have time-in-queue span"() {
    setup:
    def client = AmazonSQSAsyncClientBuilder.standard()
      .withEndpointConfiguration(endpoint)
      .withCredentials(credentialsProvider)
      .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessageBatch(new SendMessageBatchRequest()
        .withQueueUrl(queueUrl)
        .withEntries(
        sentMessages.withIndex().collect {
          new SendMessageBatchRequestEntry(
            it.second as String, it.first)
        }
        ))
    })
    def messages = client.receiveMessage(
      new ReceiveMessageRequest()
      .withQueueUrl(queueUrl)
      .withMaxNumberOfMessages(10)
      .withAttributeNames("SentTimestamp")
      ).messages.collect {
        it.body
      }

    then:
    messages.sort() == sentMessages.sort()
    assertSqsTraceWithTimeInQueue()

    cleanup:
    client.shutdown()
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
      resourceName "SQS.SendMessageBatch"
      spanType DDSpanTypes.HTTP_CLIENT
      errored false
      measured true
      childOf(parent)
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
        "aws.operation" "SendMessageBatchRequest"
        "aws.agent" "java-aws-sdk"
        "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
        defaultTags()
      }
    }
  }

  def consumerSpan(TraceAssert traceAssert, parent) {
    traceAssert.span {
      serviceName "A-service"
      operationName "aws.http"
      resourceName "SQS.ReceiveMessage"
      spanType DDSpanTypes.MESSAGE_CONSUMER
      errored false
      measured true
      childOf(parent)
      tags {
        "$Tags.COMPONENT" "java-aws-sdk"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        "aws.service" "AmazonSQS"
        "aws_service" "sqs"
        "aws.operation" "ReceiveMessageRequest"
        "aws.agent" "java-aws-sdk"
        "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
        defaultTags(parent.resourceName as String == "SQS.SendMessageBatch")
      }
    }
  }

  def timeInQueueSpan(TraceAssert traceAssert, parent) {
    traceAssert.span {
      serviceName "sqs"
      operationName "aws.http"
      resourceName "SQS.DeliverMessage"
      spanType DDSpanTypes.MESSAGE_BROKER
      errored false
      measured true
      childOf(parent)
      tags {
        "$Tags.COMPONENT" "java-aws-sdk"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_BROKER
        "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
        defaultTags(true)
      }
    }
  }
}
