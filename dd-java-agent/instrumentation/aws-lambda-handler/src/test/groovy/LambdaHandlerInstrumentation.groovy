import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared
import spock.lang.AutoCleanup
import datadog.trace.lambda.LambdaHandler
import okhttp3.HttpUrl
import datadog.communication.http.OkHttpUtils


import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

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
