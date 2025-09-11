import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import groovy.json.JsonSlurper
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse
import software.amazon.awssdk.services.eventbridge.model.Target
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import spock.lang.Shared

import java.time.Duration
import java.util.concurrent.CompletableFuture

class EventBridgeClientTest extends InstrumentationSpecification {
  static final LOCALSTACK = new GenericContainer(DockerImageName.parse("localstack/localstack:4.2.0"))
  .withExposedPorts(4566)
  .withEnv("SERVICES", "sns,sqs,events")
  .withReuse(true)
  .withStartupTimeout(Duration.ofSeconds(120))

  @Shared
  SnsClient snsClient
  @Shared
  String testTopicARN
  @Shared
  String testTopicName

  @Shared
  EventBridgeClient eventBridgeClient
  @Shared
  EventBridgeAsyncClient eventBridgeAsyncClient
  @Shared
  String testBusARN
  @Shared
  String testBusName
  @Shared
  String testRuleName

  @Shared
  SqsClient sqsClient
  @Shared
  String testQueueURL
  @Shared
  String testQueueARN

  def setupSpec() {
    LOCALSTACK.start()
    def endPoint = "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566)

    eventBridgeClient = EventBridgeClient.builder()
      .endpointOverride(URI.create(endPoint))
      .region(Region.of("us-east-1"))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
      .build()

    eventBridgeAsyncClient = EventBridgeAsyncClient.builder()
      .endpointOverride(URI.create(endPoint))
      .region(Region.of("us-east-1"))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
      .build()

    snsClient = SnsClient.builder()
      .endpointOverride(URI.create(endPoint))
      .region(Region.of("us-east-1"))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
      .build()

    sqsClient = SqsClient.builder()
      .endpointOverride(URI.create(endPoint))
      .region(Region.of("us-east-1"))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
      .build()

    // Create SNS topic for EventBridge -> SNS tests
    testTopicName = "testtopic"
    testTopicARN = snsClient.createTopic { it.name(testTopicName) }.topicArn()

    // Create EventBridge bus
    testBusName = "testbus"
    testBusARN = eventBridgeClient.createEventBus { it.name(testBusName) }.eventBusArn()

    // Create EventBridge rule
    testRuleName = "testrule"
    eventBridgeClient.putRule {
      it.name(testRuleName)
        .eventBusName(testBusName)
        .eventPattern("{\"source\": [{\"prefix\": \"com.example\"}]}")
    }

    // Create SQS queue for EventBridge -> SQS tests
    testQueueURL = sqsClient.createQueue { it.queueName("testqueue") }.queueUrl()
    testQueueARN = sqsClient.getQueueAttributes {
      it.queueUrl(testQueueURL).attributeNames(QueueAttributeName.QUEUE_ARN)
    }.attributes().get(QueueAttributeName.QUEUE_ARN)

    // Set up EventBridge rule targets
    eventBridgeClient.putTargets { req ->
      req.rule(testRuleName)
        .eventBusName(testBusName)
        .targets(
        Target.builder().id("1").arn(testQueueARN).build(),
        Target.builder().id("2").arn(testTopicARN).build()
        )
    }
  }

  def setup() {
    sqsClient.purgeQueue { it.queueUrl(testQueueURL) }
  }

  def cleanupSpec() {
    LOCALSTACK.stop()
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(GeneralConfig.SERVICE_NAME, "eventbridge")
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, "true")

    // test propagation styles
    injectSysConfig('dd.trace.propagation.style', 'datadog,b3single,b3multi,haystack,xray,tracecontext')
  }

  def "trace details propagated via EventBridge to SQS (sync)"() {
    when:
    TEST_WRITER.clear()
    eventBridgeClient.putEvents { req ->
      req.entries(
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test")
        .detail('{"message":"sometext"}')
        .eventBusName(testBusARN)
        .build()
        )
    }

    def message = sqsClient.receiveMessage { it.queueUrl(testQueueURL).waitTimeSeconds(3) }.messages().get(0)
    def messageBody = new JsonSlurper().parseText(message.body())

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "EventBridge.PutEvents"
          spanType DDSpanTypes.HTTP_CLIENT
        }
      }
    }

    def detail = messageBody["detail"]
    assert detail instanceof Map
    assert detail["message"] == "sometext"

    def traceContext = detail["_datadog"]
    assert traceContext["x-datadog-trace-id"] != null
    assert traceContext["x-datadog-trace-id"].toString().isNumber()
    assert traceContext["x-datadog-parent-id"] != null
    assert traceContext["x-datadog-parent-id"].toString().isNumber()
    assert traceContext["x-datadog-sampling-priority"] == "1"
    assert traceContext["x-datadog-start-time"] != null
    assert traceContext["x-datadog-resource-name"] != null

    assert messageBody["source"] == "com.example"
    assert messageBody["detail-type"] == "test"
  }

  def "trace details propagated via EventBridge to SQS (async)"() {
    when:
    TEST_WRITER.clear()
    CompletableFuture<PutEventsResponse> futureResponse = eventBridgeAsyncClient.putEvents { req ->
      req.entries(
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test-async")
        .detail('{"message":"async-text"}')
        .eventBusName(testBusARN)
        .build()
        )
    }
    futureResponse.get() // Wait for async operation to complete

    def message = sqsClient.receiveMessage { it.queueUrl(testQueueURL).waitTimeSeconds(3) }.messages().get(0)
    def messageBody = new JsonSlurper().parseText(message.body())

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "EventBridge.PutEvents"
          spanType DDSpanTypes.HTTP_CLIENT
        }
      }
    }

    def detail = messageBody["detail"]
    assert detail instanceof Map
    assert detail["message"] == "async-text"

    def traceContext = detail["_datadog"]
    assert traceContext["x-datadog-trace-id"].toString().isNumber()
    assert traceContext["x-datadog-parent-id"].toString().isNumber()
    assert traceContext["x-datadog-sampling-priority"] == "1"
    assert traceContext["x-datadog-start-time"] != null
    assert traceContext["x-datadog-resource-name"] != null

    assert messageBody["source"] == "com.example"
    assert messageBody["detail-type"] == "test-async"
  }

  def "trace details propagated via EventBridge to SNS"() {
    when:
    TEST_WRITER.clear()
    eventBridgeClient.putEvents { req ->
      req.entries(
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test")
        .detail('{"message":"sns-test"}')
        .eventBusName(testBusARN)
        .build()
        )
    }

    then:
    // Unlike SQS, there's no `receiveMessage()` or similar function for SnsClient,
    // so we can't test the detail contents but we can test the span's fields.
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "EventBridge.PutEvents"
          spanType DDSpanTypes.HTTP_CLIENT
        }
      }
    }
  }

  def "test sending multiple events in a single PutEvents request (sync)"() {
    when:
    TEST_WRITER.clear()
    eventBridgeClient.putEvents { req ->
      req.entries(
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test1")
        .detail('{"message":"event1"}')
        .eventBusName(testBusARN)
        .build(),
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test2")
        .detail('{"message":"event2"}')
        .eventBusName(testBusARN)
        .build()
        )
    }

    def messages = sqsClient.receiveMessage { it.queueUrl(testQueueURL).maxNumberOfMessages(2).waitTimeSeconds(5) }.messages()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "EventBridge.PutEvents"
          spanType DDSpanTypes.HTTP_CLIENT
        }
      }
    }

    assert messages.size() == 2
    messages.every { message ->
      def body = new JsonSlurper().parseText(message.body())
      body["detail"]["message"].toString().contains("event") &&
        body["detail"]["_datadog"] != null &&
        body["detail"]["_datadog"]["x-datadog-trace-id"] != null &&
        body["detail"]["_datadog"]["x-datadog-parent-id"] != null
    }
  }

  def "test sending multiple events in a single PutEvents request (async)"() {
    when:
    TEST_WRITER.clear()
    CompletableFuture<PutEventsResponse> futureResponse = eventBridgeAsyncClient.putEvents { req ->
      req.entries(
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test1-async")
        .detail('{"message":"event1-async"}')
        .eventBusName(testBusARN)
        .build(),
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test2-async")
        .detail('{"message":"event2-async"}')
        .eventBusName(testBusARN)
        .build()
        )
    }
    futureResponse.get() // Wait for async operation to complete

    def messages = sqsClient.receiveMessage { it.queueUrl(testQueueURL).maxNumberOfMessages(2).waitTimeSeconds(5) }.messages()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "EventBridge.PutEvents"
          spanType DDSpanTypes.HTTP_CLIENT
        }
      }
    }

    assert messages.size() == 2
    messages.every { message ->
      def body = new JsonSlurper().parseText(message.body())
      body["detail"]["message"].toString().contains("event") &&
        body["detail"]["_datadog"] != null &&
        body["detail"]["_datadog"]["x-datadog-trace-id"] != null &&
        body["detail"]["_datadog"]["x-datadog-parent-id"] != null
    }
  }

  def "test with nested details"() {
    when:
    TEST_WRITER.clear()
    eventBridgeClient.putEvents { req ->
      req.entries(
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test")
        .detail('{"nested":{"nested_again":{"key1":"value1","key2":42}},"array":[1,2,3]}')
        .eventBusName(testBusARN)
        .build()
        )
    }

    def message = sqsClient.receiveMessage { it.queueUrl(testQueueURL).waitTimeSeconds(3) }.messages().get(0)
    def messageBody = new JsonSlurper().parseText(message.body())

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "EventBridge.PutEvents"
          spanType DDSpanTypes.HTTP_CLIENT
        }
      }
    }

    def detail = messageBody["detail"]
    assert detail["nested"]["nested_again"]["key1"] == "value1"
    assert detail["nested"]["nested_again"]["key2"] == 42
    assert detail["array"] == [1, 2, 3]
    assert detail["_datadog"] != null
    assert detail["_datadog"]["x-datadog-start-time"] != null
    assert detail["_datadog"]["x-datadog-resource-name"] != null
  }

  def "test behavior when data streams are disabled"() {
    setup:
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, "false")

    when:
    TEST_WRITER.clear()
    eventBridgeClient.putEvents { req ->
      req.entries(
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test")
        .detail('{"message":"data streams disabled"}')
        .eventBusName(testBusARN)
        .build()
        )
    }

    def message = sqsClient.receiveMessage { it.queueUrl(testQueueURL).waitTimeSeconds(3) }.messages().get(0)
    def messageBody = new JsonSlurper().parseText(message.body())

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "EventBridge.PutEvents"
          spanType DDSpanTypes.HTTP_CLIENT
        }
      }
    }

    assert messageBody["detail"]["message"] == "data streams disabled"
    assert messageBody["detail"]["_datadog"]["x-datadog-trace-id"] != null
    assert messageBody["detail"]["_datadog"]["x-datadog-parent-id"] != null
    assert messageBody["detail"]["_datadog"]["x-datadog-tags"] != null

    cleanup:
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, "true")
  }

  def "test behavior with empty detail fields"() {
    when:
    TEST_WRITER.clear()
    eventBridgeClient.putEvents { req ->
      req.entries(
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test-empty")
        .detail('{}')
        .eventBusName(testBusARN)
        .build(),
        )
    }

    def messages = sqsClient.receiveMessage { it.queueUrl(testQueueURL).maxNumberOfMessages(2).waitTimeSeconds(5) }.messages()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "EventBridge.PutEvents"
          spanType DDSpanTypes.HTTP_CLIENT
        }
      }
    }
    assert messages.size() == 1

    def message = messages[0]
    assert message != null
    def emptyDetailBody = new JsonSlurper().parseText(message.body())
    assert emptyDetailBody["detail"]["_datadog"] != null  // Datadog context should be injected
    assert emptyDetailBody["detail"]["_datadog"]["x-datadog-trace-id"] != null
    assert emptyDetailBody["detail"]["_datadog"]["x-datadog-parent-id"] != null
    assert emptyDetailBody["detail"]["_datadog"]["x-datadog-start-time"] != null
    assert emptyDetailBody["detail"]["_datadog"]["x-datadog-resource-name"] != null
  }

  def "test propagation styles"() {
    when:
    eventBridgeClient.putEvents { req ->
      req.entries(
        PutEventsRequestEntry.builder()
        .source("com.example")
        .detailType("test")
        .detail('{"foo":"bar"}')
        .eventBusName(testBusARN)
        .build()
        )
    }

    def message = sqsClient.receiveMessage { it.queueUrl(testQueueURL).waitTimeSeconds(3) }.messages().get(0)
    def messageBody = new JsonSlurper().parseText(message.body())
    def traceContext = messageBody["detail"]["_datadog"]

    then:
    expectedHeaders.each { header ->
      assert traceContext[header] != null
    }

    where:
    expectedHeaders = [
      'x-datadog-trace-id',
      'x-datadog-parent-id',
      'x-datadog-sampling-priority',
      'b3',
      'X-B3-TraceId',
      'X-B3-SpanId',
      'Span-ID',
      'Parent-ID',
      'X-Amzn-Trace-Id',
      'traceparent',
      'tracestate'
    ]
  }
}
