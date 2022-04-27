import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.instrumentation.aws.v1.lambda.LambdaHandlerInstrumentation

class LambdaHandlerInstrumentationTest extends AgentTestRunner {

  def "test constructor"() {
    when:
    environmentVariables.set("_HANDLER", handlerEnv)
    def objTest = new LambdaHandlerInstrumentation()

    then:
    objTest.instrumentedType() == instrumentedType
    objTest.getMethodName() == methodName

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
          operationName "dd-tracer-serverless-span"
          errored false
        }
      }
    }
  }
}
