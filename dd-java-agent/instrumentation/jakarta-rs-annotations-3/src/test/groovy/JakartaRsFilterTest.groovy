import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared
import spock.lang.Unroll

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.PreMatching
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@Unroll
abstract class JakartaRsFilterTest extends AgentTestRunner {

  @Shared
  SimpleRequestFilter simpleRequestFilter = new SimpleRequestFilter()

  @Shared
  PrematchRequestFilter prematchRequestFilter = new PrematchRequestFilter()

  abstract makeRequest(String url)

  def "test #resource, #abortNormal, #abortPrematch"() {
    given:
    simpleRequestFilter.abort = abortNormal
    prematchRequestFilter.abort = abortPrematch
    def abort = abortNormal || abortPrematch

    when:
    def responseText
    def responseStatus

    // start a trace because the test doesn't go through any servlet or other instrumentation.
    runUnderTrace("test.span") {
      (responseText, responseStatus) = makeRequest(resource)
    }

    then:
    responseText == expectedResponse

    if (abort) {
      responseStatus == Response.Status.UNAUTHORIZED.statusCode
    } else {
      responseStatus == Response.Status.OK.statusCode
    }

    assertTraces(1) {
      trace(2) {
        span {
          operationName "test.span"
          resourceName parentResourceName
          tags {
            "$Tags.COMPONENT" "jakarta-rs"
            if (httpRoute) {
              "$Tags.HTTP_ROUTE" httpRoute
            }
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName abort ? "jakarta-rs.request.abort" : "jakarta-rs.request"
          resourceName controllerName
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            defaultTags()
          }
        }
      }
    }

    where:
    resource           | abortNormal | abortPrematch | parentResourceName         | httpRoute | controllerName                 | expectedResponse
    "/test/hello/bob"  | false       | false         | "POST /test/hello/{name}"  | "/test/hello/{name}" | "Test1.hello"                  | "Test1 bob!"
    "/test2/hello/bob" | false       | false         | "POST /test2/hello/{name}" | "/test2/hello/{name}" | "Test2.hello"                  | "Test2 bob!"
    "/test3/hi/bob"    | false       | false         | "POST /test3/hi/{name}"    | "/test3/hi/{name}" | "Test3.hello"                  | "Test3 bob!"

    // Resteasy and Jersey give different resource class names for just the below case
    // Resteasy returns "SubResource.class"
    // Jersey returns "Test1.class
    // "/test/hello/bob"  | true        | false         | "POST /test/hello/{name}"  | "Test1.hello"                  | "Aborted"

    "/test2/hello/bob" | true        | false         | "POST /test2/hello/{name}" | "/test2/hello/{name}" | "Test2.hello"                  | "Aborted"
    "/test3/hi/bob"    | true        | false         | "POST /test3/hi/{name}"    | "/test3/hi/{name}" | "Test3.hello"                  | "Aborted"
    "/test/hello/bob"  | false       | true          | "test.span"                | null | "PrematchRequestFilter.filter" | "Aborted Prematch"
    "/test2/hello/bob" | false       | true          | "test.span"                | null | "PrematchRequestFilter.filter" | "Aborted Prematch"
    "/test3/hi/bob"    | false       | true          | "test.span"                | null | "PrematchRequestFilter.filter" | "Aborted Prematch"
  }

  def "test nested call"() {
    given:
    simpleRequestFilter.abort = false
    prematchRequestFilter.abort = false

    when:
    def responseText
    def responseStatus

    // start a trace because the test doesn't go through any servlet or other instrumentation.
    runUnderTrace("test.span") {
      (responseText, responseStatus) = makeRequest(resource)
    }

    then:
    responseStatus == Response.Status.OK.statusCode
    responseText == expectedResponse

    assertTraces(1) {
      trace(3) {
        span {
          operationName "test.span"
          resourceName parentResourceName
          tags {
            "$Tags.COMPONENT" "jakarta-rs"
            "$Tags.HTTP_ROUTE" resource
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName "jakarta-rs.request"
          resourceName controller1Name
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            defaultTags()
          }
        }
        span {
          childOf span(1)
          operationName "jakarta-rs.request"
          resourceName controller2Name
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            defaultTags()
          }
        }
      }
    }

    where:
    resource        | parentResourceName   | controller1Name | controller2Name | expectedResponse
    "/test3/nested" | "POST /test3/nested" | "Test3.nested"  | "Test3.hello"   | "Test3 nested!"
  }

  @Provider
  class SimpleRequestFilter implements ContainerRequestFilter {
    boolean abort = false

    @Override
    void filter(ContainerRequestContext requestContext) throws IOException {
      if (abort) {
        requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
          .entity("Aborted")
          .type(MediaType.TEXT_PLAIN_TYPE)
          .build())
      }
    }
  }

  @Provider
  @PreMatching
  class PrematchRequestFilter implements ContainerRequestFilter {
    boolean abort = false

    @Override
    void filter(ContainerRequestContext requestContext) throws IOException {
      if (abort) {
        requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
          .entity("Aborted Prematch")
          .type(MediaType.TEXT_PLAIN_TYPE)
          .build())
      }
    }
  }
}

