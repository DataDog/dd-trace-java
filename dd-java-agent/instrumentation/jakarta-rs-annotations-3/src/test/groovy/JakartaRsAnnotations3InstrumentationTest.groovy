import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.function.BiFunction
import datadog.trace.api.function.Supplier
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.jakarta3.JakartaRsAnnotationsDecorator
import groovy.transform.CompileStatic
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HEAD
import jakarta.ws.rs.OPTIONS
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.PathSegment

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.gateway.Events.EVENTS
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

class JakartaRsAnnotations3InstrumentationTest extends AgentTestRunner {

  @CompileStatic
  def setupSpec() {
    // Register the Instrumentation Gateway callbacks
    def ig = get().instrumentationGateway()
    Events<IGCallbacks.Context> events = Events.get()
    ig.registerCallback(events.requestStarted(), IGCallbacks.REQUEST_STARTED_CB)
    ig.registerCallback(events.requestEnded(), IGCallbacks.REQUEST_ENDED_CB)
    ig.registerCallback(events.requestPathParams(), IGCallbacks.REQUEST_PARAMS_CB)
  }


  @CompileStatic
  class IGCallbacks {
    static class Context {
      Object pathParams
    }

    static final Supplier<Flow<Context>> REQUEST_STARTED_CB =
    ({
      ->
      new Flow.ResultFlow<Context>(new Context())
    } as Supplier<Flow<Context>>)

    static final BiFunction<RequestContext<Context>, IGSpanInfo, Flow<Void>> REQUEST_ENDED_CB =
    ({ RequestContext<Context> rqCtxt, IGSpanInfo info ->
      (info as AgentSpan).setTag('path_params', rqCtxt.data.pathParams)
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext<Context>, IGSpanInfo, Flow<Void>>)

    static final BiFunction<RequestContext<Context>, Map<String, ?>, Flow<Void>> REQUEST_PARAMS_CB = { RequestContext<Context> rqCtxt, Map<String, ?> map ->
      if (map && !map.empty) {
        def context = rqCtxt.data
        context.pathParams = map
      }
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext<Context>, Map<String, ?>, Flow<Void>>
  }

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

  static class PathSegmentImpl implements PathSegment {
    String path
    MultivaluedMap<String, String> matrixParameters
  }

  def 'path segments are published'() {
    setup:
    CallbackProvider cbp = AgentTracer.get().instrumentationGateway()
    Supplier<Flow<Object>> startedCB = cbp.getCallback(EVENTS.requestStarted())
    IGCallbacks.Context requestContextData = startedCB.get().result
    TagContext tagContext = new TagContext()
      .withRequestContextData(requestContextData)

    when:
    AgentSpan span = startSpan('top_span', tagContext, true)
    span.resourceName = 'resource name'
    span.spanType = 'web'
    AgentScope scope = activateSpan(span)
    try {
      new Object() {
          @POST
          @Path("/{param1}/{param2}/{others: .*}")
          void call(@PathParam("param1") PathSegment segment,
            @PathParam("param2") int id, @PathParam("others") List<PathSegment> others) {
          }
        }.call(new PathSegmentImpl(path: 'foo'), 42, [new PathSegmentImpl(path: 'bar')])
    } finally {
      cbp.getCallback(EVENTS.requestEnded()).apply(span.requestContext, span)
      span.finish()
      scope.close()
    }
    TEST_WRITER.waitForTraces(1)

    then:
    requestContextData.pathParams == [param1: 'foo', others: ['bar'], param2: '42']
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
