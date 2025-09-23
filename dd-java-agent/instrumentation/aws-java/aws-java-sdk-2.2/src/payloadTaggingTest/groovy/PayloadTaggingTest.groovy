import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.TracerConfig
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sns.model.Tag
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.SqsClient
import spock.lang.Shared
import java.time.Duration

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

abstract class AbstractPayloadTaggingTest extends InstrumentationSpecification {
  static final Object NA = {}

  static final int DEFAULT_PORT = 4566
  static final LOCALSTACK = new GenericContainer(DockerImageName.parse("localstack/localstack:4.2.0"))
  .withExposedPorts(DEFAULT_PORT)
  .withEnv("SERVICES", "apigateway,events,s3,sns,sqs,kinesis")
  .withReuse(true)
  .withStartupTimeout(Duration.ofSeconds(120))
  .withNetworkAliases("localstack")

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

  def setupSpec() {
    LOCALSTACK.start()
    def port = LOCALSTACK.getMappedPort(DEFAULT_PORT)
    def endpoint = URI.create("http://${LOCALSTACK.getHost()}:$port")
    def region = Region.US_EAST_1
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

  Tag snsTag(String key, String value) {
    return Tag.builder().key(key).value(value).build()
  }

  MessageAttributeValue snsBinaryAttribute(String data) {
    MessageAttributeValue.builder().dataType("Binary").binaryValue(SdkBytes.fromByteArray(data.bytes)).build()
  }
}

class PayloadTaggingRedactionForkedTest extends AbstractPayloadTaggingTest {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TracerConfig.TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, "all")
    injectSysConfig(TracerConfig.TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, "\$.*,\$.Owner.DisplayName")
  }

  def "test payload tag observability support for #service"() {
    setup:
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', apiCall)

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          spanType DDSpanTypes.HTTP_CLIENT
          childOf(span(0))
          assert expectedReqTag == NA || span.tags.get("aws.request.body." + expectedReqTag) == "tagvalue"
          assert expectedRespTag == NA || span.tags.get("aws.response.body." + expectedRespTag) == "redacted"

          assert !span.tags.containsKey("_dd.payload_tags_incomplete")
          assert !span.tags.containsKey("aws.request.body")
          assert !span.tags.containsKey("aws.response.body")
          assert !span.tags.containsValue(null)
        }
      }
    }

    where:
    service       | expectedReqTag | expectedRespTag     | apiCall
    "ApiGateway"  | "name"         | "value"             | {
      apiGatewayClient.createApiKey {
        it.name("tagvalue")
      }
    }
    "EventBridge" | "Name"         | "EventBusArn"       | {
      eventBridgeClient.createEventBus {
        it.name("tagvalue")
      }
    }
    "Sns"         | "Name"         | "TopicArn"          | {
      snsClient.createTopic {
        it.name("tagvalue")
      }
    }
    "Sqs"         | "QueueName"    | "QueueUrl"          | {
      sqsClient.createQueue {
        it.queueName("tagvalue")
      }
    }
    "Kinesis"     | "StreamName"   | NA                  | {
      kinesisClient.createStream {
        it.streamName("tagvalue")
      }
    }
    "Kinesis"     | NA             | "ShardLimit"        | { kinesisClient.describeLimits() }
    "S3"          | NA             | "Owner.DisplayName" | { s3Client.listBuckets() }
  }
}

class PayloadTaggingExpansionForkedTest extends AbstractPayloadTaggingTest {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TracerConfig.TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, "all")
    injectSysConfig(TracerConfig.TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, "\$.MessageId,\$.SubscriptionArn,\$[*].phoneNumbers")
  }

  def "support various types, embedded JSON in string and binary format #expectedReqTag"() {
    setup:
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', apiCall)

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          spanType DDSpanTypes.HTTP_CLIENT
          childOf(span(0))
          assert span.tags.get("aws.request.body." + expectedReqTag) == expectedReqTagValue

          assert !span.tags.containsKey("_dd.payload_tags_incomplete")
          assert !span.tags.containsKey("aws.request.body")
          assert !span.tags.containsKey("aws.response.body")
        }
      }
    }

    where:
    expectedReqTag                                      | expectedReqTagValue    | apiCall
    "Message.sms"                                       | "sms text"             | {
      snsClient.publish {
        it.phoneNumber("+15555555555").messageStructure("json")
          .message('{ "sms": "sms text", "default": "default text" }')
      }
    }
    "Tags.0.Key"                                        | "foo"                  | {
      snsClient.createTopic {
        it.name("testtopic")
          .tags(Tag.builder().key("foo").value("bar").build(), Tag.builder().key("t").value("1").build())
      }
    }
    "nextToken"                                         | null                   | {
      snsClient.listPhoneNumbersOptedOut()
    }
    "attributes.DefaultSMSType"                         | "bar"                  | {
      snsClient.setSMSAttributes { it.attributes(["DefaultSenderID": "foo", "DefaultSMSType": "bar"]) }
    }
    "MessageAttributes.foo\\.bar.BinaryValue.abc\\.def" | 42                     | {
      snsClient.publish {
        it.phoneNumber("+15555555555").message("testmessage")
          .messageAttributes(["foo.bar": snsBinaryAttribute('{"abc.def": 42}')
          ])
      }
    }
    "MessageAttributes.foo\\.bar.BinaryValue"           | "<binary>"             | {
      snsClient.publish {
        it.phoneNumber("+15555555555").message("testmessage").messageAttributes(["foo.bar": snsBinaryAttribute('{"invalid json: 42}')])
      }
    }
    "Message"                                           | "{\"invalid json: 42}" | {
      snsClient.publish { it.phoneNumber("+15555555555").message('{"invalid json: 42}') }
    }
    "Message.abc\\.def"                                 | 3.14d                  | {
      snsClient.publish { it.phoneNumber("+15555555555").message('{"abc.def": 3.14}') }
    }
    "Message.abc\\.def.0"                               | null                   | {
      snsClient.publish { it.phoneNumber("+15555555555").message('{"abc.def": [null]}') }
    }
  }
}

class PayloadTaggingMaxDepthForkedTest extends AbstractPayloadTaggingTest {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TracerConfig.TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, "all")
    injectSysConfig(TracerConfig.TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, "all")
    injectSysConfig(TracerConfig.TRACE_CLOUD_PAYLOAD_TAGGING_MAX_DEPTH, "4")
  }

  def "generate tags up to the specified max depth #expectedReqTag"() {
    setup:
    TEST_WRITER.clear()

    expect:
    Config.get().getCloudPayloadTaggingMaxDepth() == 4

    when:
    TraceUtils.runUnderTrace('parent', apiCall)

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          spanType DDSpanTypes.HTTP_CLIENT
          childOf(span(0))
          assert span.tags.get("aws.request.body." + expectedReqTag) != null
          assert span.tags.get("aws.request.body." + missingReqTag) == null

          assert !span.tags.containsKey("_dd.payload_tags_incomplete")
          assert !span.tags.containsKey("aws.request.body")
          assert !span.tags.containsKey("aws.response.body")
        }
      }
    }

    where:
    expectedReqTag     | missingReqTag         | apiCall
    "Message.a2.a3.a4" | "Message.a2.a3.b4.a5" | {
      snsClient.publish { it.phoneNumber("+15555555555").message('{ "a2": { "a3" : { "a4" : 42, "b4" : { "a5" : 33 } } } }') }
    }
    "Message.0.0.0"    | "Message.0.0.1"       | {
      snsClient.publish { it.phoneNumber("+15555555555").message('[ [ [ 3, [ "a4" ] ] ] ]') }
    }
    "Message.a2.a3.a4" | "Message.a2.a3.b4"    | {
      snsClient.publish { it.phoneNumber("+15555555555").message('{ "a2": \'{ "a3" : { "a4" : 42, "b4" : { "a5" : 33 } } }\' }') }
    }
    "Message.0.0.0"    | "Message.0.0.1"       | {
      snsClient.publish { it.phoneNumber("+15555555555").message('[ [ \'[ 3, [ "a4" ] ]\' ] ]') }
    }
  }
}

class PayloadTaggingMaxTagsForkedTest extends AbstractPayloadTaggingTest {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TracerConfig.TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING, "all")
    injectSysConfig(TracerConfig.TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING, "all")
    injectSysConfig(TracerConfig.TRACE_CLOUD_PAYLOAD_TAGGING_MAX_TAGS, "5")
  }

  def "generate tags up to the specified max number #iterationIndex"() {
    setup:
    TEST_WRITER.clear()

    expect:
    Config.get().getCloudPayloadTaggingMaxTags() == 5

    when:
    TraceUtils.runUnderTrace('parent', apiCall)

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          spanType DDSpanTypes.HTTP_CLIENT
          childOf(span(0))

          def reqTags = span.tags.keySet().stream().filter { it.startsWith("aws.request.body.") }.count()
          def respTags = span.tags.keySet().stream().filter { it.startsWith("aws.response.body.") }.count()

          assert reqTags + respTags == 5
          assert span.tags.containsKey("_dd.payload_tags_incomplete")
          assert !span.tags.containsKey("aws.request.body")
          assert !span.tags.containsKey("aws.response.body")
        }
      }
    }

    where:
    apiCall << [
      {
        snsClient.createTopic {
          it.name("testtopic")
            .tags(
            snsTag("a", "1"),
            snsTag("b", "2"),
            snsTag("c", "3"),
            snsTag("d", "4"),
            snsTag("e", "5"),
            )
        }
      },
      {
        snsClient.listPhoneNumbersOptedOut()
      }
    ]
  }
}

