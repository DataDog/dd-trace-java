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
            "error.type" "java.lang.Error"
            "error.message" "Some error"
            "error.stack" String
            "language" "jvm"
            "process_id" Long
            "runtime-id" String
            "thread.id" Long
            "thread.name" String
            "_dd.profiling.ctx" "test"
            "_dd.profiling.enabled" 0
            "_dd.agent_psr" 1.0
            "_dd.tracer_host" String
            "_sample_rate" 1
            "_dd.trace_span_attribute_schema" 0
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
