package datadog.trace.bootstrap

import datadog.trace.bootstrap.instrumentation.httpurlconnection.LambdaHandler
import datadog.trace.test.util.DDSpecification

class LambdaHandlerTest extends DDSpecification {

  def "test writeValueAsString"() {
    when:
    def objTest = LambdaHandler.writeValueAsString(obj)
    def resultTest = expectedResult

    then:
    objTest == resultTest

    where:
    expectedResult    | obj
    "test"            | new String("toto")
  }
}
