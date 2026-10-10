package datadog.trace.instrumentation.springweb6

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.springframework.http.HttpStatus
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.server.ResponseStatusException

import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.DECORATE

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

  static class CustomStatusException extends RuntimeException {
    private final int status

    CustomStatusException(int status) {
      this.status = status
    }

    int httpCode() {
      return status
    }
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
            errorTags(throwable.class, throwable.message)
            defaultTags()
          }
        }
      }
    }

    where:
    throwable << [
      new CustomNotFoundException(),
      new CustomNotFoundExceptionUsingCodeAttribute(),
      new ResponseStatusException(HttpStatus.NOT_FOUND),
      new ErrorResponseException(HttpStatus.NOT_FOUND)
    ]
  }

  def "exception with an embedded 5xx status is flagged as an error"() {
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
          errored true
          tags {
            "$Tags.COMPONENT" "spring-web-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            errorTags(throwable.class, throwable.message)
            defaultTags()
          }
        }
      }
    }

    where:
    throwable << [
      new CustomServerErrorException(),
      new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR),
      new ErrorResponseException(HttpStatus.INTERNAL_SERVER_ERROR)
    ]
  }

  def "exception recognized via configured custom accessor with a non-5xx status is not flagged as an error"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESPONSE_STATUS_EXCEPTIONS, "${CustomStatusException.name}#httpCode")
    def testSpan = AgentTracer.startSpan("spring-web-controller", "spring.handler")
    def scope = AgentTracer.activateSpan(testSpan)
    DECORATE.afterStart(testSpan)
    def throwable = new CustomStatusException(404)

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
            errorTags(CustomStatusException)
            defaultTags()
          }
        }
      }
    }
  }

  def "exception recognized via configured custom accessor with a 5xx status is flagged as an error"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESPONSE_STATUS_EXCEPTIONS, "${CustomStatusException.name}#httpCode")
    def testSpan = AgentTracer.startSpan("spring-web-controller", "spring.handler")
    def scope = AgentTracer.activateSpan(testSpan)
    DECORATE.afterStart(testSpan)
    def throwable = new CustomStatusException(500)

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
            errorTags(CustomStatusException)
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
