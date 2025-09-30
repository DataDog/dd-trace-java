import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.instrumentation.aws.ExpectedQueryParams
import org.springframework.cloud.aws.messaging.listener.annotation.SqsListener
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cloud.aws.messaging.config.annotation.EnableSqs
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.messaging.handler.annotation.Headers
import org.springframework.messaging.handler.annotation.Payload
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

abstract class SpringSqsClientTest extends VersionedNamingTestBase {

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
      try {
        server.stopAndWait()
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt()
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

    messages.forEach {/* consume to create message spans */ }

    then:
    def sendSpan
    assertTraces(1) {
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
            urlTags("http://localhost:${address.port}/", ExpectedQueryParams.getExpectedQueryParams("SendMessage"))
            defaultTags()
          }
        }
        sendSpan = span(1)
      }
    }

    // Verify that we received a message
    assert messages.size() > 0
    assert messages[0].body() == 'sometext'

    cleanup:
    client.close()
  }
}

class SpringSqsClientV0Test extends SpringSqsClientTest {

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

class SpringSqsClientV1ForkedTest extends SpringSqsClientTest {

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

class SpringSqsClientV0DataStreamsTest extends SpringSqsClientTest {
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

class SpringSqsClientV1DataStreamsForkedTest extends SpringSqsClientTest {
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

// Spring SQS Listener Test
abstract class SpringSqsListenerTest extends VersionedNamingTestBase {

  def setup() {
    System.setProperty(SdkSystemSetting.AWS_ACCESS_KEY_ID.property(), "my-access-key")
    System.setProperty(SdkSystemSetting.AWS_SECRET_ACCESS_KEY.property(), "my-secret-key")
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
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

  def "test Spring SQS listener receives message and creates spans"() {
    setup:
    def client = SqsClient.builder()
      .region(Region.EU_CENTRAL_1)
      .endpointOverride(endpoint)
      .credentialsProvider(credentialsProvider)
      .build()
    def queueUrl = client.createQueue(CreateQueueRequest.builder().queueName('test-queue').build()).queueUrl()
    TEST_WRITER.clear()

    // Set up Spring application with SQS listener
    System.setProperty("sqs.queue.name", "test-queue")
    System.setProperty("cloud.aws.sqs.endpoint", endpoint.toString())
    System.setProperty("cloud.aws.region.static", "eu-central-1")
    System.setProperty("cloud.aws.credentials.access-key", "my-access-key")
    System.setProperty("cloud.aws.credentials.secret-key", "my-secret-key")

    def application = SpringApplication.run(TestSpringSqsApplication.class)
    def messageReceiver = application.getBean(MessageReceiver.class)

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody('hello from spring').build())
    })

    // Wait for the message to be processed by the listener
    assert messageReceiver.latch.await(10, TimeUnit.SECONDS)

    then:
    def sendSpan
    assertTraces(1) {
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
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "SendMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/test-queue"
            "aws.requestId" "00000000-0000-0000-0000-000000000000"
            urlTags("http://localhost:${address.port}/", ExpectedQueryParams.getExpectedQueryParams("SendMessage"))
            defaultTags()
          }
        }
        sendSpan = span(1)
      }
    }

    // Verify the message was received
    assert messageReceiver.receivedMessage == 'hello from spring'
    assert messageReceiver.receivedHeaders != null

    cleanup:
    application.close()
    client.close()
  }
}

@SpringBootApplication
@EnableSqs
class TestSpringSqsApplication {
  @Bean
  MessageReceiver messageReceiver() {
    return new MessageReceiver()
  }
}

class MessageReceiver {
  CountDownLatch latch = new CountDownLatch(1)
  String receivedMessage
  Map<String, Object> receivedHeaders

  @SqsListener(value = "\${sqs.queue.name}")
  public void receiveMessage(@Payload String message, @Headers Map<String, Object> headers) {
    println "=== SPAN DEBUG: Spring SQS Listener ==="
    println "Received message: $message"
    println "Headers: $headers"
    
    this.receivedMessage = message
    this.receivedHeaders = headers
    latch.countDown()
  }
}

class SpringSqsListenerV0Test extends SpringSqsListenerTest {
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

class SpringSqsListenerV1ForkedTest extends SpringSqsListenerTest {
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
