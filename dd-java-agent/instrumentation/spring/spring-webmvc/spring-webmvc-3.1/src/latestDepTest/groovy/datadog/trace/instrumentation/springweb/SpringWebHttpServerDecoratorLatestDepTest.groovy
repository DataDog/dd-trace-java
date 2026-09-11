package datadog.trace.instrumentation.springweb

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE

// ResponseStatusException was added in Spring 5.0, which is only guaranteed to be on the
// classpath for the latestDepTest suite (this module's base test classpath is Spring 3.1). This
// exercises the reflective ResponseStatusException handling in SpringWebHttpServerDecorator.
class SpringWebHttpServerDecoratorLatestDepTest extends InstrumentationSpecification {

  def "ResponseStatusException with a non-5xx status is not flagged as an error"() {
    setup:
    def testSpan = AgentTracer.startSpan("spring-web-controller", "spring.handler")
    def scope = AgentTracer.activateSpan(testSpan)
    DECORATE.afterStart(testSpan)
    def throwable = new ResponseStatusException(HttpStatus.NOT_FOUND)

    when:
    DECORATE.onError(testSpan, throwable)
    DECORATE.beforeFinish(testSpan)
    scope.close()
    testSpan.finish()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spring.handler"
          spanType "web"
          errored false
          tags {
            "$Tags.COMPONENT" "spring-web-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            errorTags(ResponseStatusException, throwable.message)
            defaultTags()
          }
        }
      }
    }
  }

  def "ResponseStatusException with a 5xx status is flagged as an error"() {
    setup:
    def testSpan = AgentTracer.startSpan("spring-web-controller", "spring.handler")
    def scope = AgentTracer.activateSpan(testSpan)
    DECORATE.afterStart(testSpan)
    def throwable = new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR)

    when:
    DECORATE.onError(testSpan, throwable)
    DECORATE.beforeFinish(testSpan)
    scope.close()
    testSpan.finish()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spring.handler"
          spanType "web"
          errored true
          tags {
            "$Tags.COMPONENT" "spring-web-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            errorTags(ResponseStatusException, throwable.message)
            defaultTags()
          }
        }
      }
    }
  }
}
