import datadog.trace.agent.test.AgentTestRunner

class LambdaHandlerInstrumentation extends AgentTestRunner {

  def "test lambda handler"() {
    when:

    environmentVariables.set("_HANDLER", "Handler")
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
