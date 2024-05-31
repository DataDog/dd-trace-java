
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import spock.lang.Shared
import groovy.json.JsonSlurper

import java.time.Duration

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

abstract class SnsClientTest extends VersionedNamingTestBase {
  static final LOCALSTACK = new GenericContainer(DockerImageName.parse("localstack/localstack"))
  .withExposedPorts(4566) // Default LocalStack port
  .withEnv("SERVICES", "sns,sqs") // Enable SNS and SQS service
  .withReuse(true)
  .withStartupTimeout(Duration.ofSeconds(120))

  @Shared SnsClient snsClient
  @Shared SqsClient sqsClient

  @Shared String testQueueURL
  @Shared String testQueueARN
  @Shared String testTopicARN

  def setupSpec() {
    LOCALSTACK.start()
    def endPoint = "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566)
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
    testQueueURL = sqsClient.createQueue {  it.queueName("testqueue") }.queueUrl()
    testQueueARN = sqsClient.getQueueAttributes {it.queueUrl(testQueueURL).attributeNames(QueueAttributeName.QUEUE_ARN)}.attributes().get(QueueAttributeName.QUEUE_ARN)
    testTopicARN = snsClient.createTopic { it.name("testtopic") }.topicArn()
    snsClient.subscribe {it.topicArn(testTopicARN).protocol("sqs").endpoint(testQueueARN)}
  }

  def cleanupSpec() {
    LOCALSTACK.stop()
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // Set a service name that gets sorted early with SORT_BY_NAMES
    injectSysConfig(GeneralConfig.SERVICE_NAME, "A-service")
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

  def "trace details propagated via SNS system message attributes"() {
    when:
    TEST_WRITER.clear()
    PublishResponse response
    TraceUtils.runUnderTrace('parent', {
      response = snsClient.publish { it.message("sometext").topicArn(testTopicARN)}
    })

    def message = sqsClient.receiveMessage {it.queueUrl(testQueueURL).waitTimeSeconds(3)}.messages().get(0)
    def jsonSlurper = new JsonSlurper()
    def messageBody = jsonSlurper.parseText(message.body())
    def endPoint = "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566)

    then:
    def sendSpan
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName expectedService("SNS", "Publish")
          operationName expectedOperation("SNS", "Publish")
          resourceName "Sns.Publish"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" endPoint+'/'
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" LOCALSTACK.getMappedPort(4566)
            "$Tags.PEER_HOSTNAME" LOCALSTACK.getHost()
            "aws.service" "Sns"
            "aws_service" "Sns"
            "aws.operation" "Publish"
            "aws.agent" "java-aws-sdk"
            "aws.topic.name" "testtopic"
            "topicname" "testtopic"
            "aws.requestId" response.responseMetadata().requestId()
            defaultTags()
          }
        }
        sendSpan = span(1)
      }
    }

    and:
    messageBody["Message"] == "sometext"
    String base64EncodedString = messageBody["MessageAttributes"]["_datadog"]["Value"]
    byte[] decodedBytes = base64EncodedString.decodeBase64()
    String decodedString = new String(decodedBytes, "UTF-8")
    JsonSlurper slurper = new JsonSlurper()
    Map traceContextInJson = slurper.parseText(decodedString)
    traceContextInJson['x-datadog-trace-id'] == sendSpan.traceId.toString()
    traceContextInJson['x-datadog-parent-id'] == sendSpan.spanId.toString()
    traceContextInJson['x-datadog-sampling-priority'] == "1"
  }
}

class SnsClientV0Test extends SnsClientTest {

  @Override
  String expectedOperation(String awsService, String awsOperation) {
    if ("SNS" == awsService) {
      return "aws.http"
    }
    return "http.request"
  }

  @Override
  String expectedService(String awsService, String awsOperation) {
    if ("SNS" == awsService) {
      return "sns"
    }
    return "A-service"
  }

  @Override
  int version() {
    0
  }
}

class SnsClientV1ForkedTest extends SnsClientTest {

  @Override
  String expectedOperation(String awsService, String awsOperation) {
    if (awsService == "Sns" && awsOperation == "Publish") {
      return "aws.sns.send"
    }
    return "http.client.request"
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

