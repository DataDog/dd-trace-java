import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.jaxrs2.JaxRsAnnotationsDecorator
import io.dropwizard.jersey.PATCH

import javax.ws.rs.DELETE
import javax.ws.rs.GET
import javax.ws.rs.HEAD
import javax.ws.rs.OPTIONS
import javax.ws.rs.POST
import javax.ws.rs.PUT
import javax.ws.rs.Path

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
