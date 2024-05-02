import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.sns.AmazonSNSClientBuilder
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import spock.lang.Shared
import groovy.json.JsonSlurper

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SNS
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS

abstract class SnsClientTest extends VersionedNamingTestBase {
  static final localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:latest"))
  .withServices( SQS, SNS)
  @Shared AmazonSNSClient snsClient
  @Shared SqsClient sqsClient

  @Shared String testQueueURL
  @Shared String testQueueARN
  @Shared String testTopicARN

  def setupSpec() {
    localstack.start()
    snsClient = AmazonSNSClientBuilder.standard()
      .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration( localstack.getEndpointOverride(SNS).toString(), localstack.getRegion()))
      .withCredentials( new AWSStaticCredentialsProvider(new BasicAWSCredentials("test", "test")))
      .build()
    sqsClient = SqsClient.builder()
      .endpointOverride(localstack.getEndpointOverride(SQS))
      .region(Region.of(localstack.getRegion()))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
      .build()
    testQueueURL = sqsClient.createQueue {  it.queueName("testqueue") }.queueUrl()
    testQueueARN = sqsClient.getQueueAttributes {it.queueUrl(testQueueURL).attributeNames(QueueAttributeName.QUEUE_ARN)}.attributes().get(QueueAttributeName.QUEUE_ARN)
    testTopicARN = snsClient.createTopic("testtopic").topicArn
    snsClient.subscribe(testTopicARN, "sqs", testQueueARN)
  }

  def cleanupSpec() {
    localstack.stop()
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
    TraceUtils.runUnderTrace('parent', {
      snsClient.publish(testTopicARN, 'sometext')
    })

    def message = sqsClient.receiveMessage {it.queueUrl(testQueueURL).waitTimeSeconds(3)}.messages().get(0)
    def jsonSlurper = new JsonSlurper()
    def messageBody = jsonSlurper.parseText(message.body())

    then:
    def sendSpan
    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName expectedService("SNS", "Publish")
          operationName expectedOperation("SNS", "Publish")
          resourceName "SNS.Publish"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" localstack.getEndpointOverride(SNS).toString()+'/'
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" localstack.getEndpointOverride(SNS).port
            "$Tags.PEER_HOSTNAME" localstack.getEndpointOverride(SNS).host
            "aws.service" "AmazonSNS"
            "aws_service" "sns"
            "aws.endpoint" localstack.getEndpointOverride(SNS).toString()
            "aws.operation" "PublishRequest"
            "aws.agent" "java-aws-sdk"
            "aws.topic.name" "testtopic"
            "topicname" "testtopic"
            defaultTags()
          }
        }
        sendSpan = span(1)
      }
      trace(1) {
        span {
          serviceName expectedService("None", "http.post")
          operationName expectedOperation("None", "http.post")
          resourceName "POST /"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" localstack.getEndpointOverride(SNS).toString()+'/'
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" localstack.getEndpointOverride(SNS).port
            "$Tags.PEER_HOSTNAME" localstack.getEndpointOverride(SNS).host
            defaultTags(true)
          }
        }
      }
    }

    and:
    messageBody["Message"] == "sometext"
    messageBody["MessageAttributes"]["X-Amzn-Trace-Id"]["Value"] =~
      /Root=1-[0-9a-f]{8}-00000000${sendSpan.traceId.toHexStringPadded(16)};Parent=${DDSpanId.toHexStringPadded(sendSpan.spanId)};Sampled=1/
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
    if (awsService == "SNS"&& awsOperation == "Publish") {
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

