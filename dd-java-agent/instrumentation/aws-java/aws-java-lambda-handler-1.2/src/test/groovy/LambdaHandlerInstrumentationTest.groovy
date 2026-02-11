import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import java.nio.charset.StandardCharsets
import com.amazonaws.services.lambda.runtime.Context

abstract class LambdaHandlerInstrumentationTest extends VersionedNamingTestBase {
  def requestId = "test-request-id"

  // Must set this env var before the Datadog integration is initialized.
  // If present at load time, the integration auto-enables.
  static {
    environmentVariables.set("_HANDLER", "Handler")
  }

  @Override
  String service() {
    null
  }

  def "test lambda streaming handler"() {
    when:
    def input = new ByteArrayInputStream(StandardCharsets.UTF_8.encode("Hello").array())
    def output = new ByteArrayOutputStream()
    def ctx = Stub(Context) {
      getAwsRequestId() >> requestId
    }
    new HandlerStreaming().handleRequest(input, output, ctx)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          spanType DDSpanTypes.SERVERLESS
          errored false
        }
      }
    }
  }

  def "test streaming handler with error"() {
    when:
    def input = new ByteArrayInputStream(StandardCharsets.UTF_8.encode("Hello").array())
    def output = new ByteArrayOutputStream()
    def ctx = Stub(Context) {
      getAwsRequestId() >> requestId
    }
    new HandlerStreamingWithError().handleRequest(input, output, ctx)

    then:
    thrown(Error)
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          spanType DDSpanTypes.SERVERLESS
          errored true
          tags {
            tag "request_id", requestId
            tag "error.type", "java.lang.Error"
            tag "error.message", "Some error"
            tag "error.stack", String
            tag "language", "jvm"
            tag "process_id", Long
            tag "runtime-id", String
            tag "thread.id", Long
            tag "thread.name", String
            tag "_dd.profiling.ctx", "test"
            tag "_dd.profiling.enabled", 0
            tag "_dd.agent_psr", 1.0
            tag "_dd.tracer_host", String
            tag "_sample_rate", 1
            tag "_dd.trace_span_attribute_schema", { it != null }
          }
        }
      }
    }
  }
}


class LambdaHandlerInstrumentationV0Test extends LambdaHandlerInstrumentationTest {
  @Override
  int version() {
    0
  }

  @Override
  String operation() {
    "dd-tracer-serverless-span"
  }
}

class LambdaHandlerInstrumentationV1ForkedTest extends LambdaHandlerInstrumentationTest {
  @Override
  int version() {
    1
  }

  @Override
  String operation() {
    "aws.lambda.invoke"
  }
}
