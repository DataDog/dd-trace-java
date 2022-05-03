package datadog.trace.lambda

import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class LambdaHandlerTest extends DDCoreSpecification {

  class TestObject {

    public String field1
    public boolean field2

    TestObject() {
      this.field1 = "toto"
      this.field2 = true
    }
  }

  def "test start invocation success"() {
    given:
    def server = httpServer {
      handlers {
        post("/lambda/start-invocation") {
          response
            .status(200)
            .addHeader("x-datadog-trace-id", "1234")
            .addHeader("x-datadog-span-id", "5678")
            .send()
        }
      }
    }
    LambdaHandler.setExtensionBaseUrl(server.address.toString())

    when:
    def objTest = LambdaHandler.notifyStartInvocation(obj)

    then:
    objTest.getTraceId().toString() == traceId
    objTest.getSpanId().toString() == spanId

    cleanup:
    server.close()

    where:
    traceId    | spanId      | obj
    "1234"     | "5678"      | new TestObject()
  }

  def "test start invocation failure"() {
    given:
    def server = httpServer {
      handlers {
        post("/lambda/start-invocation") {
          response
            .status(500)
            .send()
        }
      }
    }
    LambdaHandler.setExtensionBaseUrl(server.address.toString())

    when:
    def objTest = LambdaHandler.notifyStartInvocation(obj)

    then:
    objTest == expected

    cleanup:
    server.close()

    where:
    expected    | obj
    null        | new TestObject()
  }

  def "test end invocation success"() {
    given:
    def server = httpServer {
      handlers {
        post("/lambda/end-invocation") {
          response
            .status(200)
            .send()
        }
      }
    }
    LambdaHandler.setExtensionBaseUrl(server.address.toString())

    when:
    def result = LambdaHandler.notifyEndInvocation(boolValue)
    server.lastRequest.headers.get("x-datadog-invocation-error") == headerValue

    then:
    result == expected

    cleanup:
    server.close()

    where:
    expected  | headerValue     | boolValue
    true      | "true"          | true
    true      | null            | false
  }

  def "test end invocation failure"() {
    given:
    def server = httpServer {
      handlers {
        post("/lambda/end-invocation") {
          response
            .status(500)
            .send()
        }
      }
    }
    LambdaHandler.setExtensionBaseUrl(server.address.toString())

    when:
    def result = LambdaHandler.notifyEndInvocation(boolValue)

    then:
    result == expected
    server.lastRequest.headers.get("x-datadog-invocation-error") == headerValue

    cleanup:
    server.close()

    where:
    expected  | headerValue     | boolValue
    false     | "true"          | true
    false     | null            | false
  }
}