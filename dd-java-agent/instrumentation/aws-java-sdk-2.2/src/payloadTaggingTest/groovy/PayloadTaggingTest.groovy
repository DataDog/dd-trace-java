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
import software.amazon.awssdk.services.sqs.SqsClient
import spock.lang.Shared
import java.time.Duration

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

final class PayloadTaggingForkedTest extends PayloadTaggingTest {}

class PayloadTaggingTest extends AgentTestRunner {

  static final LOCALSTACK = new GenericContainer(DockerImageName.parse("localstack/localstack"))
  .withExposedPorts(4566) // Default LocalStack port
  .withEnv("SERVICES", "apigateway,events,sqs")
  .withReuse(true)
  .withStartupTimeout(Duration.ofSeconds(120))

  @Shared
  SqsClient sqsClient
  @Shared
  ApiGatewayClient apiGatewayClient
  @Shared
  EventBridgeClient eventBridgeClient

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TracerConfig.TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, "\$.*")
    injectSysConfig(TracerConfig.TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, "\$.*")
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

    sqsClient = SqsClient.builder()
      .endpointOverride(endpoint)
      .region(region)
      .credentialsProvider(credentialsProvider)
      .build()
  }

  def cleanupSpec() {
    LOCALSTACK.stop()
  }

  def "extract payload fields as tags for #service"() {
    when:
    TEST_WRITER.clear()

    TraceUtils.runUnderTrace('parent', {
      requestClosure()
    })

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          spanType DDSpanTypes.HTTP_CLIENT
          childOf(span(0))
          assert span.tags.get(requestRedactedTag) == "redacted"
          assert span.tags.get(responseRedactedTag) == "redacted"
        }
      }
    }

    where:
    service       | requestRedactedTag           | responseRedactedTag             | requestClosure
    "ApiGateway"  | "aws.request.body.name"      | "aws.response.body.value"      | { apiGatewayClient.createApiKey { it.name("testapi") } }
    "EventBridge" | "aws.request.body.Name"      | "aws.response.body.EventBusArn" | { eventBridgeClient.createEventBus { it.name("testbus") } }
    "Sqs"         | "aws.request.body.QueueName" | "aws.response.body.QueueUrl"    | { sqsClient.createQueue { it.queueName("testqueue") } }
  }
}

