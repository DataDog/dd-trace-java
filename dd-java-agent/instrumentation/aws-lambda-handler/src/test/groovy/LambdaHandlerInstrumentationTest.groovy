import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.instrumentation.aws.v1.lambda.LambdaHandlerInstrumentation

abstract class LambdaHandlerInstrumentationTest extends VersionedNamingTestBase {

  @Override
  String service() {
    null
  }

  def "test constructor"() {
    when:
    environmentVariables.set("_HANDLER", handlerEnv)
    def objTest = new LambdaHandlerInstrumentation()

    then:
    objTest.configuredMatchingType() == instrumentedType
    objTest.getMethodName() == methodName
    environmentVariables.clear("_HANDLER")

    where:
    instrumentedType     | methodName        | handlerEnv
    "example.Handler"    | "mySuperHandler"  | "example.Handler::mySuperHandler"
    "package.type"       | "handleRequest"   | "package.type"
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
