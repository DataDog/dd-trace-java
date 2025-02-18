import datadog.trace.agent.test.naming.VersionedNamingTestBase
import java.nio.charset.StandardCharsets

abstract class LambdaHandlerInstrumentationTest extends VersionedNamingTestBase {

  @Override
  String service() {
    null
  }

  def "test lambda streaming handler"() {
    when:
    def input = new ByteArrayInputStream(StandardCharsets.UTF_8.encode("Hello").array())
    def output = new ByteArrayOutputStream()
    new HandlerStreaming().handleRequest(input, output, null)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          errored false
        }
      }
    }
  }

  def "test streaming handler with error"() {
    when:
    def input = new ByteArrayInputStream(StandardCharsets.UTF_8.encode("Hello").array())
    def output = new ByteArrayOutputStream()
    new HandlerStreamingWithError().handleRequest(input, output, null)

    then:
    thrown(Error)
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          errored true
          tags {
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
