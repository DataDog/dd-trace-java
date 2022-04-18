package datadog.trace.bootstrap

import datadog.trace.bootstrap.instrumentation.httpurlconnection.LambdaHandler
import datadog.trace.test.util.DDSpecification


class LambdaHandlerTest extends DDSpecification {

  class TestObject {

    public String field1
    public boolean field2

    public TestObject() {
      this.field1 = "toto"
      this.field2 = true
    }
  }

  def "test writeValueAsString"() {
    when:
    def objTest = LambdaHandler.writeValueAsString(obj)
    def resultTest = expectedResult

    then:
    objTest == resultTest

    where:
    expectedResult                                       | obj
    "{\"field1\":\"toto\",\"field2\":true}"              | new TestObject()
    "null"                                               | null
  }
}
