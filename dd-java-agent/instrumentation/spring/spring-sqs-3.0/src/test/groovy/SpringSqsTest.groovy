import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.PortUtils
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import io.awspring.cloud.sqs.config.SqsBootstrapConfiguration
import io.awspring.cloud.sqs.annotation.SqsListener
import spock.lang.Shared
import software.amazon.awssdk.services.sqs.SqsAsyncClient

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS

class SpringSqsTest extends InstrumentationSpecification {

  static final String QUEUE_NAME = "test-queue"

  @Shared
  LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.2.0"))
  .withServices(SQS)

  @Override
  def setupSpec() {
    localstack.start()

    def endpoint = localstack.getEndpointOverride(SQS).toString()

    PortUtils.waitForPortToOpen(
      localstack.getHost(),
      localstack.getMappedPort(4566),
      10,
      TimeUnit.SECONDS)

    def sqsClient = SqsAsyncClient.builder()
      .endpointOverride(new URI(endpoint))
      .region(software.amazon.awssdk.regions.Region.US_EAST_1)
      .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
      software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
      .build()
    sqsClient.createQueue(CreateQueueRequest.builder().queueName(QUEUE_NAME).build()).get()
  }

  @Override
  def cleanupSpec() {
    if (null != localstack) {
      localstack.close()
    }
  }

  def "test basic SQS message send and receive"() {
    setup:
    def endpoint = localstack.getEndpointOverride(SQS).toString()
    def sqsClient = SqsAsyncClient.builder()
      .endpointOverride(new URI(endpoint))
      .region(software.amazon.awssdk.regions.Region.US_EAST_1)
      .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
      software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
      .build()
    def queueUrl = sqsClient
      .getQueueUrl(GetQueueUrlRequest.builder().queueName(QUEUE_NAME).build())
      .get()
      .queueUrl()


    when:

    def latch = new CountDownLatch(1)
    def received = new AtomicReference<String>()

    TestConfig.endpoint = endpoint
    TestConfig.latch = latch
    TestConfig.received = received

    // Create a configuration class that properly sets up Spring SQS
    def ctx = new AnnotationConfigApplicationContext(TestConfig)

    // various setup actions are traced
    TEST_WRITER.waitForTraces(2)
    TEST_WRITER.clear()

    runUnderTrace("parent") {
      sqsClient
        .sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody("hello").build())
        .get()
    }

    then:
    latch.await(2, TimeUnit.SECONDS)
    received.get() == "hello"
    assertTraces(3) {
      trace(2, true) {
        span(0) {
          childOf(span(1))
          operationName "aws.http"
          resourceName "Sqs.SendMessage"
          spanType "http"
        }
        span(1) {
          operationName "parent"
        }
      }
      trace(2, true) {
        span(0) {
          operationName "aws.http"
          resourceName "Sqs.ReceiveMessage"
          spanType "queue"
        }
        span(1) {
          // the main test is here. spring.consume needs to be the child of ReceiveMessage
          childOf(span(0))
          operationName "spring.consume"
          resourceName "TestListener.onMessage"
          spanType "queue"
        }
      }
      trace(1) {
        span(0) {
          operationName "aws.http"
          resourceName "Sqs.DeleteMessageBatch"
          spanType "http"
        }
      }
    }

    cleanup:
    ctx?.close()
    sqsClient?.close()
  }

  @Configuration
  @Import(SqsBootstrapConfiguration)
  static class TestConfig {
    static String endpoint
    static CountDownLatch latch
    static AtomicReference<String> received

    @Bean
    SqsAsyncClient sqsAsyncClient() {
      return SqsAsyncClient.builder()
        .endpointOverride(new URI(endpoint))
        .region(software.amazon.awssdk.regions.Region.US_EAST_1)
        .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
        .build()
    }

    @Bean
    SqsMessageListenerContainerFactory<Object> defaultSqsListenerContainerFactory() {
      def factory = new SqsMessageListenerContainerFactory<Object>()
      factory.setSqsAsyncClient(sqsAsyncClient())
      return factory
    }

    @Bean
    TestListener testListener() {
      return new TestListener(latch, received)
    }
  }

  @Component
  static class TestListener {
    private final CountDownLatch latch
    private final AtomicReference<String> received

    TestListener(CountDownLatch latch, AtomicReference<String> received) {
      this.latch = latch
      this.received = received
    }

    @SqsListener(queueNames = QUEUE_NAME)
    void onMessage(String payload) {
      received.set(payload)
      latch.countDown()
    }
  }
}
