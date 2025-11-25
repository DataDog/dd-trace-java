import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import groovy.json.JsonSlurper
import java.time.Duration
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.SfnException
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse
import spock.lang.Shared

abstract class SfnClientTest extends VersionedNamingTestBase {
  @Shared GenericContainer localStack
  @Shared SfnClient sfnClient
  @Shared String testStateMachineARN
  @Shared Object endPoint

  def setupSpec() {
    localStack = new GenericContainer(DockerImageName.parse("localstack/localstack:4.2.0"))
      .withExposedPorts(4566)
      .withEnv("SERVICES", "stepfunctions")
      .withReuse(true)
      .withStartupTimeout(Duration.ofSeconds(120))
    localStack.start()
    endPoint = "http://" + localStack.getHost() + ":" + localStack.getMappedPort(4566)
    sfnClient = SfnClient.builder()
      .endpointOverride(URI.create(endPoint))
      .region(Region.US_EAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
      .build()

    def response = sfnClient.createStateMachine { builder ->
      builder.name("testStateMachine")
        .definition("{\"StartAt\": \"HelloWorld\", \"States\": {\"HelloWorld\": {\"Type\": \"Pass\", \"End\": true}}}")
        .build()
    }
    testStateMachineARN = response.stateMachineArn()
  }

  def cleanupSpec() {
    sfnClient.close()
    localStack.stop()
  }

  def "Step Functions span is created"() {
    when:
    StartExecutionResponse response
    TraceUtils.runUnderTrace('parent', {
      response = sfnClient.startExecution { builder ->
        builder.stateMachineArn(testStateMachineARN)
          .input("{\"key\": \"value\"}")
          .build()
      }
    })

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation()
          resourceName "Sfn.StartExecution"
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
            "$Tags.PEER_PORT" localStack.getMappedPort(4566)
            "$Tags.PEER_HOSTNAME" localStack.getHost()
            "aws.service" "Sfn"
            "aws.operation" "StartExecution"
            "aws.agent" "java-aws-sdk"
            "aws.requestId" response.responseMetadata().requestId()
            "aws_service" "Sfn"
            defaultTags()
          }
        }
      }
    }
  }

  def "Trace context is injected to Step Functions input"() {
    when:
    StartExecutionResponse response
    TraceUtils.runUnderTrace('parent', {
      response = sfnClient.startExecution { builder ->
        builder.stateMachineArn(testStateMachineARN)
          .input("{\"key\": \"value\"}")
          .build()
      }
    })

    then:
    def execution = sfnClient.describeExecution { builder ->
      builder.executionArn(response.executionArn())
        .build()
    }
    def input = new JsonSlurper().parseText(execution.input())
    input["key"] == "value"
    input["_datadog"]["x-datadog-trace-id"] != null
    input["_datadog"]["x-datadog-parent-id"] != null
    input["_datadog"]["x-datadog-tags"] != null
  }

  def "datadog context is not injected when SfnInjectDatadogAttribute is disabled"() {
    setup:
    injectSysConfig("sfn.inject.datadog.attribute.enabled", "false")

    when:
    StartExecutionResponse response = sfnClient.startExecution { builder ->
      builder.stateMachineArn(testStateMachineARN)
        .input("{\"key\": \"value\"}")
        .build()
    }

    then:
    def execution = sfnClient.describeExecution { builder ->
      builder.executionArn(response.executionArn())
        .build()
    }

    def input = new JsonSlurper().parseText(execution.input())
    assert input["key"] == "value"
    assert input["_datadog"] == null
  }

  def "AWS rejects invalid JSON but instrumentation does not error"() {
    when:
    sfnClient.startExecution { b ->
      b.stateMachineArn(testStateMachineARN)
        .input("hello") // invalid JSON
        .build()
    }

    then:
    thrown(SfnException)
  }

  def "Doesn't cause error for Step Functions input edge cases"() {
    def inputs = [
      '''{}''',
      '''{  }''',
      ''' { } ''',
      '''{"foo": "bar"}''',
      '''  {  "foo"  :  "bar"  }  ''',
      '''{"key1": "val1", "key2": "val2"}''',
      '''  {  "key1"  :  "val1"   ,   "key2" : "val2"  } '''
    ]

    when:
    inputs.forEach { input ->
      TraceUtils.runUnderTrace('parent', {
        sfnClient.startExecution { builder ->
          builder.stateMachineArn(testStateMachineARN)
            .input(input)
            .build()
        }
      })
    }

    then:
    noExceptionThrown()
  }
}

class SfnClientV0Test extends SfnClientTest {
  @Override
  int version() {
    0
  }

  @Override
  String service() {
    return "java-aws-sdk"
  }

  @Override
  String operation() {
    return "aws.http"
  }
}
