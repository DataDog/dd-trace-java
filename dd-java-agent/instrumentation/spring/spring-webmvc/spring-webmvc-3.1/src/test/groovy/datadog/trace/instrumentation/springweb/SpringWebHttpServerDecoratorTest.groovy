package datadog.trace.instrumentation.springweb

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE

// ResponseStatusException was added in Spring 5.0 and isn't available on this module's base
// (Spring 3.1) test classpath. Its handling is covered separately, on the latestDepTest
// classpath, by SpringWebHttpServerDecoratorLatestDepTest.
class SpringWebHttpServerDecoratorTest extends InstrumentationSpecification {

  @ResponseStatus(HttpStatus.NOT_FOUND)
  static class CustomNotFoundException extends RuntimeException {
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  static class CustomServerErrorException extends RuntimeException {
  }

  // Spring's @AliasFor("value") on ResponseStatus#code isn't honored by plain reflection, so
  // setting only code() (rather than value()) exercises a separate code path.
  @ResponseStatus(code = HttpStatus.NOT_FOUND)
  static class CustomNotFoundExceptionUsingCodeAttribute extends RuntimeException {
  }

  def "exception with an embedded non-5xx status is not flagged as an error"() {
    setup:
    def testSpan = AgentTracer.startSpan("spring-web-controller", "spring.handler")
    def scope = AgentTracer.activateSpan(testSpan)
    DECORATE.afterStart(testSpan)

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
            errorTags(throwable.class)
            defaultTags()
          }
        }
      }
    }

    where:
    throwable << [new CustomNotFoundException(), new CustomNotFoundExceptionUsingCodeAttribute()]
  }

  def "exception with an embedded 5xx status is flagged as an error"() {
    setup:
    def testSpan = AgentTracer.startSpan("spring-web-controller", "spring.handler")
    def scope = AgentTracer.activateSpan(testSpan)
    DECORATE.afterStart(testSpan)
    def throwable = new CustomServerErrorException()

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
            errorTags(CustomServerErrorException)
            defaultTags()
          }
        }
      }
    }
  }

  def "plain exception is still flagged as an error"() {
    setup:
    def testSpan = AgentTracer.startSpan("spring-web-controller", "spring.handler")
    def scope = AgentTracer.activateSpan(testSpan)
    DECORATE.afterStart(testSpan)
    def throwable = new IllegalStateException("boom")

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
            errorTags(IllegalStateException, "boom")
            defaultTags()
          }
        }
      }
    }
  }
}
