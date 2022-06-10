package datadog.trace.lambda

import datadog.trace.api.Config
import datadog.trace.core.propagation.DatadogTags
import datadog.trace.core.test.DDCoreSpecification
import datadog.trace.api.DDId
import datadog.trace.core.DDSpan
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
    Config config = Mock(Config)
    config.isServicePropagationEnabled() >> true
    config.getDataDogTagsLimit() >> 512

    def server = httpServer {
      handlers {
        post("/lambda/start-invocation") {
          response
            .status(200)
            .addHeader("x-datadog-trace-id", "1234")
            .addHeader("x-datadog-sampling-priority", "2")
            .send()
        }
      }
    }
    LambdaHandler.setExtensionBaseUrl(server.address.toString())

    when:
    def objTest = LambdaHandler.notifyStartInvocation(obj, DatadogTags.factory(config))

    then:
    objTest.getTraceId().toString() == traceId
    objTest.getSamplingPriority() == samplingPriority

    cleanup:
    server.close()

    where:
    traceId    | samplingPriority      | obj
    "1234"     | 2                     | new TestObject()
  }

  def "test start invocation failure"() {
    given:
    Config config = Mock(Config)
    config.isServicePropagationEnabled() >> true
    config.getDataDogTagsLimit() >> 512

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
    def objTest = LambdaHandler.notifyStartInvocation(obj, DatadogTags.factory(config))

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
    DDSpan span = Mock(DDSpan) {
      getTraceId() >> DDId.from("1234")
      getSpanId() >> DDId.from("5678")
      getSamplingPriority() >> 2
    }

    when:
    def result = LambdaHandler.notifyEndInvocation(span, boolValue)

    then:
    server.lastRequest.headers.get("x-datadog-invocation-error") == eHeaderValue
    server.lastRequest.headers.get("x-datadog-trace-id") == tIdHeaderValue
    server.lastRequest.headers.get("x-datadog-span-id") == sIdHeaderValue
    server.lastRequest.headers.get("x-datadog-sampling-priority") == sPIdHeaderValue
    result == expected

    cleanup:
    server.close()

    where:
    expected | eHeaderValue | tIdHeaderValue | sIdHeaderValue | sPIdHeaderValue | boolValue
    true     | "true"       | "1234"         | "5678"         | "2"             | true
    true     | null         | "1234"         | "5678"         | "2"             | false
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
    DDSpan span = Mock(DDSpan) {
      getTraceId() >> DDId.from("1234")
      getSpanId() >> DDId.from("5678")
      getSamplingPriority() >> 2
    }

    when:
    def result = LambdaHandler.notifyEndInvocation(span, boolValue)

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
