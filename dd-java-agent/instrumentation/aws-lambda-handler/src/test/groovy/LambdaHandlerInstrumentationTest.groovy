import datadog.trace.agent.test.naming.VersionedNamingTestBase
import java.nio.charset.StandardCharsets

abstract class LambdaHandlerInstrumentationTest extends VersionedNamingTestBase {

  @Override
  String service() {
    null
  }

  def "test lambda handler"() {
    when:
    new Handler().handleRequest(null, null)

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
