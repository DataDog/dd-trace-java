import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.jaxrs2.JaxRsAnnotationsDecorator
import io.dropwizard.jersey.PATCH

import javax.ws.rs.DELETE
import javax.ws.rs.GET
import javax.ws.rs.HEAD
import javax.ws.rs.NotFoundException
import javax.ws.rs.OPTIONS
import javax.ws.rs.POST
import javax.ws.rs.PUT
import javax.ws.rs.Path
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.Response

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class JaxRsAnnotations2InstrumentationTest extends InstrumentationSpecification {

  def "instrumentation can be used as root span and resource is set to METHOD PATH"() {
    setup:
    new Jax() {
        @POST
        @Path("/a")
        void call() {
        }
      }.call()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jax-rs.request"
          resourceName "POST /a"
          spanType "web"
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            "$Tags.HTTP_ROUTE" "/a"
            defaultTags()
          }
        }
      }
    }
  }

  def "span named '#name' from annotations on class when is not root span"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.JAX_RS_ADDITIONAL_ANNOTATIONS, "CustomMethod")
    runUnderTrace("test") {
      obj.call()
    }

    expect:
    assertTraces(1) {
      trace(2) {
        span {
          operationName "test"
          resourceName name
          parent()
          tags {
            "$Tags.COMPONENT" "jax-rs"
            "$Tags.HTTP_ROUTE" name.split(" ").last()
            withCustomIntegrationName(null)
            defaultTags()
          }
        }
        span {
          operationName "jax-rs.request"
          resourceName "${className}.call"
          spanType "web"
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            defaultTags()
          }
        }
      }
    }

    where:
    name                 | obj
    "/a"                 | new Jax() {
        @Path("/a")
        void call() {
        }
      }
    "GET /b"             | new Jax() {
        @GET
        @Path("/b")
        void call() {
        }
      }
    "POST /interface/c"  | new InterfaceWithPath() {
        @POST
        @Path("/c")
        void call() {
        }
      }
    "HEAD /interface"    | new InterfaceWithPath() {
        @HEAD
        void call() {
        }
      }
    "POST /abstract/d"   | new AbstractClassWithPath() {
        @POST
        @Path("/d")
        void call() {
        }
      }
    "PATCH /interface"    | new InterfaceWithPath() {
        @PATCH
        void call() {
        }
      }
    "CUSTOM /interface"    | new InterfaceWithPath() {
        @CustomMethod
        void call() {
        }
      }
    "PUT /abstract"      | new AbstractClassWithPath() {
        @PUT
        void call() {
        }
      }
    "OPTIONS /child/e"   | new ChildClassWithPath() {
        @OPTIONS
        @Path("/e")
        void call() {
        }
      }
    "DELETE /child/call" | new ChildClassWithPath() {
        @DELETE
        void call() {
        }
      }
    "POST /child/call"   | new ChildClassWithPath()
    "GET /child/call"    | new JavaInterfaces.ChildClassOnInterface()
    // TODO: uncomment when we drop support for Java 7
    //    "GET /child/invoke"         | new JavaInterfaces.DefaultChildClassOnInterface()

    className = JaxRsAnnotationsDecorator.DECORATE.className(obj.class)
  }

  def "resource method exception with an embedded non-5xx status is not flagged as an error"() {
    setup:
    def obj = new Jax() {
        @GET
        @Path("/not-found")
        void call() {
          throw new NotFoundException()
        }
      }

    when:
    obj.call()

    then:
    thrown(NotFoundException)
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jax-rs.request"
          resourceName "GET /not-found"
          spanType "web"
          errored false
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            "$Tags.HTTP_ROUTE" "/not-found"
            errorTags(NotFoundException, "HTTP 404 Not Found")
            defaultTags()
          }
        }
      }
    }
  }

  def "resource method exception with an embedded 5xx status is flagged as an error"() {
    setup:
    def obj = new Jax() {
        @GET
        @Path("/internal-error")
        void call() {
          throw new WebApplicationException(500)
        }
      }

    when:
    obj.call()

    then:
    thrown(WebApplicationException)
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jax-rs.request"
          resourceName "GET /internal-error"
          spanType "web"
          errored true
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            "$Tags.HTTP_ROUTE" "/internal-error"
            errorTags(WebApplicationException, "HTTP 500 Internal Server Error")
            defaultTags()
          }
        }
      }
    }
  }

  def "resource method exception with an embedded out-of-range status falls back to normal error handling"() {
    setup:
    // A misbehaving custom Response reporting a negative "unknown" status must not be used to
    // index into the configured server-error statuses.
    def response = Stub(Response) {
      getStatus() >> -1
      getStatusInfo() >> Stub(Response.StatusType) {
        getStatusCode() >> -1
        getReasonPhrase() >> "Custom"
        getFamily() >> Response.Status.Family.OTHER
      }
    }
    def obj = new Jax() {
        @GET
        @Path("/custom-status")
        void call() {
          throw new WebApplicationException(response)
        }
      }

    when:
    obj.call()

    then:
    def ex = thrown(WebApplicationException)
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jax-rs.request"
          resourceName "GET /custom-status"
          spanType "web"
          errored true
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            "$Tags.HTTP_ROUTE" "/custom-status"
            errorTags(WebApplicationException, ex.message)
            defaultTags()
          }
        }
      }
    }
  }

  def "resource method with a plain exception is still flagged as an error"() {
    setup:
    def obj = new Jax() {
        @GET
        @Path("/boom")
        void call() {
          throw new IllegalStateException("boom")
        }
      }

    when:
    obj.call()

    then:
    thrown(IllegalStateException)
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jax-rs.request"
          resourceName "GET /boom"
          spanType "web"
          errored true
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            "$Tags.HTTP_ROUTE" "/boom"
            errorTags(IllegalStateException, "boom")
            defaultTags()
          }
        }
      }
    }
  }

  def "resource method exception recognized via configured custom accessor with a non-5xx status is not flagged as an error"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESPONSE_STATUS_EXCEPTIONS, "${CustomStatusException.name}#httpCode")
    def obj = new Jax() {
        @GET
        @Path("/custom-not-found")
        void call() {
          throw new CustomStatusException(404)
        }
      }

    when:
    obj.call()

    then:
    thrown(CustomStatusException)
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jax-rs.request"
          resourceName "GET /custom-not-found"
          spanType "web"
          errored false
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            "$Tags.HTTP_ROUTE" "/custom-not-found"
            errorTags(CustomStatusException)
            defaultTags()
          }
        }
      }
    }
  }

  def "resource method exception recognized via configured custom accessor with a 5xx status is flagged as an error"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESPONSE_STATUS_EXCEPTIONS, "${CustomStatusException.name}#httpCode")
    def obj = new Jax() {
        @GET
        @Path("/custom-internal-error")
        void call() {
          throw new CustomStatusException(500)
        }
      }

    when:
    obj.call()

    then:
    thrown(CustomStatusException)
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jax-rs.request"
          resourceName "GET /custom-internal-error"
          spanType "web"
          errored true
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            "$Tags.HTTP_ROUTE" "/custom-internal-error"
            errorTags(CustomStatusException)
            defaultTags()
          }
        }
      }
    }
  }

  def "no annotations has no effect"() {
    setup:
    def obj = new Jax() {
        void call() {
        }
      }
    runUnderTrace("test") {
      obj.call()
    }

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "test"
          resourceName "test"
          tags {
            defaultTags()
          }
        }
      }
    }
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

  interface Jax {
    void call()
  }

  @Path("/interface")
  interface InterfaceWithPath extends Jax {
    @GET
    void call()
  }

  @Path("/abstract")
  abstract class AbstractClassWithPath implements Jax {
    @PUT
    abstract void call()
  }

  @Path("child")
  class ChildClassWithPath extends AbstractClassWithPath {
    @Path("call")
    @POST
    void call() {
    }
  }
}
