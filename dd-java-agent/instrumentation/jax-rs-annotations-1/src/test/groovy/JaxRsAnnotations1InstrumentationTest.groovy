import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.WeakMap
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.jaxrs1.JaxRsAnnotationsDecorator

import javax.ws.rs.DELETE
import javax.ws.rs.GET
import javax.ws.rs.HEAD
import javax.ws.rs.OPTIONS
import javax.ws.rs.POST
import javax.ws.rs.PUT
import javax.ws.rs.Path
import java.lang.reflect.Method

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class JaxRsAnnotations1InstrumentationTest extends AgentTestRunner {

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
      trace(0, 1) {
        span(0) {
          operationName "jax-rs.request"
          resourceName "POST /a"
          spanType "web"
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            defaultTags()
          }
        }
      }
    }
  }

  def "span named '#name' from annotations on class when is not root span"() {
    setup:
    def startingCacheSize = resourceNames.size()
    runUnderTrace("test") {
      obj.call()
    }

    expect:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          operationName "test"
          resourceName name
          parent()
          tags {
            "$Tags.COMPONENT" "jax-rs"
            defaultTags()
          }
        }
        span(1) {
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
    resourceNames.size() == startingCacheSize + 1
    resourceNames.get(obj.class).size() == 1

    when: "multiple calls to the same method"
    runUnderTrace("test") {
      (1..10).each {
        obj.call()
      }
    }
    then: "doesn't increase the cache size"
    resourceNames.size() == startingCacheSize + 1
    resourceNames.get(obj.class).size() == 1

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
    // "GET /child/invoke"  | new JavaInterfaces.DefaultChildClassOnInterface()

    className = getClassName(obj.class)

    // JavaInterfaces classes are loaded on a different classloader, so we need to find the right cache instance.
    decorator = obj.class.classLoader.loadClass(JaxRsAnnotationsDecorator.name).getField("DECORATE").get(null)
    resourceNames = (WeakMap<Class, Map<Method, String>>) decorator.resourceNames
  }

  def "no annotations has no effect"() {
    setup:
    runUnderTrace("test") {
      obj.call()
    }

    expect:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
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
    new Jax() {
      void call() {
      }
    }   | _
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
