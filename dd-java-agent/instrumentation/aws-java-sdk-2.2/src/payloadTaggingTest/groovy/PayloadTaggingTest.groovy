import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.TracerConfig
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sqs.SqsClient
import spock.lang.Shared
import java.time.Duration

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

abstract class AbstractPayloadTaggingTest extends AgentTestRunner {

  static final LOCALSTACK = new GenericContainer(DockerImageName.parse("localstack/localstack"))
  .withExposedPorts(4566) // Default LocalStack port
  .withEnv("SERVICES", "apigateway,events,sns,sqs,s3,kinesis")
  .withReuse(true)
  .withStartupTimeout(Duration.ofSeconds(120))

  @Shared
  ApiGatewayClient apiGatewayClient

  @Shared
  EventBridgeClient eventBridgeClient

  @Shared
  SnsClient snsClient

  @Shared
  SqsClient sqsClient

  @Shared
  S3Client s3Client

  @Shared
  KinesisClient kinesisClient

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
  }

  def setupSpec() {
    LOCALSTACK.start()
    def port = LOCALSTACK.getMappedPort(4566)
    def endpoint = URI.create("http://${LOCALSTACK.getHost()}:$port")
    def region = Region.of("us-east-1")
    def credentials = AwsBasicCredentials.create("test", "test")
    def credentialsProvider = StaticCredentialsProvider.create(credentials)

    apiGatewayClient = ApiGatewayClient.builder()
      .endpointOverride(endpoint)
      .region(region)
      .credentialsProvider(credentialsProvider)
      .build()

    eventBridgeClient = EventBridgeClient.builder()
      .endpointOverride(endpoint)
      .region(region)
      .credentialsProvider(credentialsProvider)
      .build()

    snsClient = SnsClient.builder()
      .endpointOverride(endpoint)
      .region(region)
      .credentialsProvider(credentialsProvider)
      .build()

    sqsClient = SqsClient.builder()
      .endpointOverride(endpoint)
      .region(region)
      .credentialsProvider(credentialsProvider)
      .build()

    s3Client = S3Client.builder()
      .endpointOverride(endpoint)
      .region(region)
      .credentialsProvider(credentialsProvider)
      .build()

    kinesisClient = KinesisClient.builder()
      .endpointOverride(endpoint)
      .region(region)
      .credentialsProvider(credentialsProvider)
      .build()
  }

  def cleanupSpec() {
    LOCALSTACK.stop()
  }
}

class PayloadTaggingRedactionForkedTest extends AbstractPayloadTaggingTest {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    def redactTopLevelTags = "\$.*"
    injectSysConfig(TracerConfig.TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, redactTopLevelTags)
    injectSysConfig(TracerConfig.TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, redactTopLevelTags)
  }

  def "redact top-level payload fields as tags for #service"() {
    when:
    TEST_WRITER.clear()

    TraceUtils.runUnderTrace('parent', {
      apiCall()
    })

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          spanType DDSpanTypes.HTTP_CLIENT
          childOf(span(0))
          assert expectedReqTag == null || span.tags.get("aws.request.body." + expectedReqTag) == "redacted"
          assert expectedRespTag == null || span.tags.get("aws.response.body." + expectedRespTag) == "redacted"
        }
      }
    }

    where:
    service       | expectedReqTag                | expectedRespTag      | apiCall
    "ApiGateway"  | "name"                        | "value"              | { apiGatewayClient.createApiKey { it.name("testapi") } }
    "EventBridge" | "Name"                        | "EventBusArn"        | { eventBridgeClient.createEventBus { it.name("testbus") } }
    "Sns"         | "Name"                        | "TopicArn"           | { snsClient.createTopic { it.name("testtopic") } }
    "Sqs"         | "QueueName"                   | "QueueUrl"           | { sqsClient.createQueue { it.queueName("testqueue") } }
    "S3"          | "x-amz-expected-bucket-owner" | "LocationConstraint" | { s3Client.getBucketLocation { it.bucket("testbucket") } }
    "Kinesis"     | "StreamModeDetails"           | null                 | { kinesisClient.createStream { it.streamName("teststream") } }
    "Kinesis"     | null                          | "ShardLimit"         | { kinesisClient.describeLimits() }
  }
}

class PayloadTaggingExpansionForkedTest extends AbstractPayloadTaggingTest {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TracerConfig.TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, "all")
    injectSysConfig(TracerConfig.TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, "\$.MessageId")
  }

  def "extract inner request payload fields as tags for #service"() {
    when:
    TEST_WRITER.clear()

    TraceUtils.runUnderTrace('parent', {
      apiCall()
    })

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          spanType DDSpanTypes.HTTP_CLIENT
          childOf(span(0))
          assert span.tags.get("aws.request.body." + expectedReqTag) == expectedReqTagValue
          assert span.tags.get("aws.response.body." + expectedRespTag) == expectedRespTagValue
        }
      }
    }

    where:
    service | expectedReqTag | expectedReqTagValue | expectedRespTag | expectedRespTagValue | apiCall
    "Sns"   | "Message.sms"  | "sms text"          | "MessageId"     | "redacted"           | {
      snsClient.publish { it.phoneNumber("+15555555555").messageStructure("json").message('{ "sms": "sms text", "default": "default text" }') }
    }
  }
}
