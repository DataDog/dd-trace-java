import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes

import static org.junit.jupiter.api.Assertions.fail

class WebServiceTest extends InstrumentationSpecification {

  def "test successful interface request is traced"() {
    when:
    new TestService1Impl().send("success")

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jakarta-ws.request"
          resourceName "TestService1Impl.send"
          spanType DDSpanTypes.SOAP
          errored false
          parent()
          tags {
            "component" "jakarta-ws-endpoint"
            defaultTags()
          }
        }
      }
    }
  }

  def "test successful class request is traced"() {
    when:
    new TestService2().send("success")

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jakarta-ws.request"
          resourceName "TestService2.send"
          spanType DDSpanTypes.SOAP
          errored false
          parent()
          tags {
            "component" "jakarta-ws-endpoint"
            defaultTags()
          }
        }
      }
    }
  }

  def "test failing interface request is traced"() {
    when:
    try {
      new TestService1Impl().send("fail")
      fail("expected exception")
    } catch (IllegalArgumentException e) {
      // expected
    }

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jakarta-ws.request"
          resourceName "TestService1Impl.send"
          spanType DDSpanTypes.SOAP
          errored true
          parent()
          tags {
            "component" "jakarta-ws-endpoint"
            "error.message" "bad request"
            "error.type" IllegalArgumentException.name
            "error.stack" String
            defaultTags()
          }
        }
      }
    }
  }

  def "test failing class request is traced"() {
    when:
    try {
      new TestService2().send("fail")
      fail("expected exception")
    } catch (IllegalArgumentException e) {
      // expected
    }

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jakarta-ws.request"
          resourceName "TestService2.send"
          spanType DDSpanTypes.SOAP
          errored true
          parent()
          tags {
            "component" "jakarta-ws-endpoint"
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
    new TestService1Impl().random()
    new TestService2().random()

    then:
    assertTraces(0) {}
  }
}
