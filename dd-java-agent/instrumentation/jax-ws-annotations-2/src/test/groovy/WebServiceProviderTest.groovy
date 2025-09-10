import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes

import static org.junit.jupiter.api.Assertions.fail

class WebServiceProviderTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.jax-ws.enabled", "true")
  }

  def "test successful request is traced"() {
    when:
    new TestProvider().invoke("success")

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jax-ws.request"
          resourceName "TestProvider.invoke"
          spanType DDSpanTypes.SOAP
          errored false
          parent()
          tags {
            "component" "jax-ws-endpoint"
            defaultTags()
          }
        }
      }
    }
  }

  def "test failing request is traced"() {
    when:
    try {
      new TestProvider().invoke("fail")
      fail("expected exception")
    } catch (IllegalArgumentException e) {
      // expected
    }

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jax-ws.request"
          resourceName "TestProvider.invoke"
          spanType DDSpanTypes.SOAP
          errored true
          parent()
          tags {
            "component" "jax-ws-endpoint"
            "error.message" "bad request"
            "error.type" IllegalArgumentException.name
            "error.stack" String
            defaultTags()
          }
        }
      }
    }
  }

  def "test other methods are not traced"() {
    when:
    new TestProvider().random()

    then:
    assertTraces(0) {}
  }
}
