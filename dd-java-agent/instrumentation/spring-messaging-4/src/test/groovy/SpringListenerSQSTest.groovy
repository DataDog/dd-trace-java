import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import io.awspring.cloud.sqs.operations.SqsTemplate
import listener.Config
import org.elasticmq.rest.sqs.SQSRestServer
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import software.amazon.awssdk.services.sqs.SqsAsyncClient

class SpringListenerSQSTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(GeneralConfig.SERVICE_NAME, "my-service")
  }

  def "receiving message context used when no immediate context"() {
    setup:
    def context = new AnnotationConfigApplicationContext(Config)
    def address = context.getBean(SQSRestServer).waitUntilStarted().localAddress()
    def template = SqsTemplate.newTemplate(context.getBean(SqsAsyncClient))
    TEST_WRITER.waitForTraces(2)
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace("parent") {
      template.sendAsync("SpringListenerSQS", "a message")
    }

    then:
    def sendingSpan
    assertTraces(4, SORT_TRACES_BY_START) {
      trace(3) {
        sendMessage(it, address, span(2))
        getQueueUrl(it, address, span(2))
        basicSpan(it, "parent")
        sendingSpan = span(0)
      }
      trace(1) {
        receiveMessage(it, address, sendingSpan)
      }
      trace(1) {
        springSqsListener(it, sendingSpan)
      }
      trace(1) {
        deleteMessageBatch(it, address)
      }
    }
  }

  static sendMessage(TraceAssert traceAssert, InetSocketAddress address, DDSpan parentSpan) {
    traceAssert.span {
      serviceName "sqs"
      operationName "aws.http"
      resourceName "Sqs.SendMessage"
      spanType DDSpanTypes.HTTP_CLIENT
      errored false
      measured true
      childOf(parentSpan)
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
        "aws.queue.url" "http://localhost:${address.port}/000000000000/SpringListenerSQS"
        "aws.requestId" "00000000-0000-0000-0000-000000000000"
        defaultTags()
      }
    }
  }

  static getQueueUrl(TraceAssert traceAssert, InetSocketAddress address, DDSpan parentSpan) {
    traceAssert.span {
      serviceName "java-aws-sdk"
      operationName "aws.http"
      resourceName "Sqs.GetQueueUrl"
      spanType DDSpanTypes.HTTP_CLIENT
      errored false
      measured true
      childOf(parentSpan)
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
        "aws.operation" "GetQueueUrl"
        "aws.agent" "java-aws-sdk"
        "aws.queue.name" "SpringListenerSQS"
        "aws.requestId" "00000000-0000-0000-0000-000000000000"
        "queuename" "SpringListenerSQS"
        defaultTags()
      }
    }
  }

  static receiveMessage(TraceAssert traceAssert, InetSocketAddress address, DDSpan parentSpan) {
    traceAssert.span {
      serviceName "sqs"
      operationName "aws.http"
      resourceName "Sqs.ReceiveMessage"
      spanType DDSpanTypes.MESSAGE_CONSUMER
      errored false
      measured true
      childOf(parentSpan)
      tags {
        "$Tags.COMPONENT" "java-aws-sdk"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        "aws.service" "Sqs"
        "aws_service" "Sqs"
        "aws.operation" "ReceiveMessage"
        "aws.agent" "java-aws-sdk"
        "aws.queue.url" "http://localhost:${address.port}/000000000000/SpringListenerSQS"
        "aws.requestId" "00000000-0000-0000-0000-000000000000"
        defaultTags(true)
      }
    }
  }

  static springSqsListener(TraceAssert traceAssert, DDSpan parentSpan) {
    traceAssert.span {
      serviceName "my-service"
      operationName "spring.consume"
      resourceName "TestListener.observe"
      spanType DDSpanTypes.MESSAGE_CONSUMER
      errored false
      measured true
      childOf(parentSpan)
      tags {
        "$Tags.COMPONENT" "spring-messaging"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        defaultTags(true)
      }
    }
  }

  static deleteMessageBatch(TraceAssert traceAssert, InetSocketAddress address) {
    traceAssert.span {
      serviceName "sqs"
      operationName "aws.http"
      resourceName "Sqs.DeleteMessageBatch"
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
        "aws.operation" "DeleteMessageBatch"
        "aws.agent" "java-aws-sdk"
        "aws.queue.url" "http://localhost:${address.port}/000000000000/SpringListenerSQS"
        "aws.requestId" "00000000-0000-0000-0000-000000000000"
        defaultTags()
      }
    }
  }
}
