import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.jakarta3.JakartaRsAnnotationsDecorator

import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HEAD
import jakarta.ws.rs.OPTIONS
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class JakartaRsAnnotations3InstrumentationTest extends AgentTestRunner {

  def "instrumentation can be used as root span and resource is set to METHOD PATH"() {
    setup:
    new Jakarta() {
        @POST
        @Path("/a")
        void call() {
        }
      }.call()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "jakarta-rs.request"
          resourceName "POST /a"
          spanType "web"
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            "$Tags.HTTP_ROUTE" "/a"
            defaultTags()
          }
        }
      }
    }
  }

  def "span named '#name' from annotations on class when is not root span"() {
    setup:
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
            "$Tags.COMPONENT" "jakarta-rs"
            "$Tags.HTTP_ROUTE" name.split(" ").last()
            defaultTags()
          }
        }
        span {
          operationName "jakarta-rs.request"
          resourceName "${className}.call"
          spanType "web"
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            defaultTags()
          }
        }
      }
    }

    where:
    name                 | obj
    "/a"                 | new Jakarta() {
        @Path("/a")
        void call() {
        }
      }
    "GET /b"             | new Jakarta() {
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

    className = JakartaRsAnnotationsDecorator.DECORATE.className(obj.class)
  }

  def "no annotations has no effect"() {
    setup:
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

    where:
    obj | _
    new Jakarta() {
        void call() {
        }
      }   | _
  }

  interface Jakarta {
    void call()
  }

  @Path("/interface")
  interface InterfaceWithPath extends Jakarta {
    @GET
    void call()
  }

  @Path("/abstract")
  abstract class AbstractClassWithPath implements Jakarta {
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
