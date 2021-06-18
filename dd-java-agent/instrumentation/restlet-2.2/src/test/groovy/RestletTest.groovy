package datadog.trace.instrumentation.restlet

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.restlet.Application
import org.restlet.Component
import org.restlet.Request
import org.restlet.Response
import org.restlet.Restlet
import org.restlet.data.Protocol
import org.restlet.Server
import org.restlet.data.Status
import org.restlet.representation.Representation
import org.restlet.representation.StringRepresentation
import org.restlet.resource.ResourceException
import org.restlet.routing.Router
import org.restlet.service.StatusService

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class RestletTest extends HttpServerTest<Component> {

  class RestletServer implements HttpServer {
    def port = 0
    Component restletComponent
    Server restletServer

    RestletServer() {
      restletComponent = new Component()
      restletServer = restletComponent.getServers().add(Protocol.HTTP, 0)
      restletComponent.getDefaultHost().attachDefault(new App())
    }

    @Override
    void start() {
      restletComponent.start()
      port = restletServer.getActualPort()
      assert port > 0
    }

    @Override
    void stop() {
      restletComponent.stop()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }
  }

  @Override
  HttpServer server() {
    return new RestletServer()
  }

  String getContext() {
    "restlet"
  }

  @Override
  String component() {
    return "restlet-http-server"
  }

  @Override
  String expectedOperationName() {
    return "restlet-http.request"
  }

  @Override
  int spanCount(ServerEndpoint endpoint) {
    if (endpoint == NOT_FOUND) {
      return super.spanCount(endpoint) - 1
    }
    return super.spanCount(endpoint)
  }

  @Override
  String testPathParam() {
    "/path/{id}/param"
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  boolean tagServerSpanWithRoute(ServerEndpoint endpoint) {
    endpoint != NOT_FOUND
  }

  @Override
  void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    boolean tagServerSpanWithRoute = tagServerSpanWithRoute(endpoint)
    trace.span {
      serviceName expectedServiceName()
      operationName expectedOperationName()
      resourceName endpoint.resource(method, address, testPathParam())
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint.errored
      if (parentID != null) {
        traceId traceID
        parentId parentID
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_PORT" Integer
        "$Tags.PEER_HOST_IPV4" { it == "127.0.0.1" || (endpoint == FORWARDED && it == endpoint.body) }
        "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_STATUS" endpoint.status
        if (endpoint == FORWARDED) {
          "$Tags.HTTP_FORWARDED_IP" endpoint.body
        }
        if (tagServerSpanWithRoute) {
          "$Tags.HTTP_ROUTE" String
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" endpoint.query
        }
        defaultTags(true)
      }
    }
  }

  String capitalize(String word) {
    Character.toUpperCase(word.charAt(0)).toString() + word.substring(1)
  }

  String capitalize(ServerEndpoint endpoint) {
    if (endpoint == QUERY_PARAM) {
      return "QueryParam"
    } else if (endpoint == PATH_PARAM) {
      return "PathParam"
    }
    return capitalize(endpoint.name().toLowerCase())
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    if (endpoint == NOT_FOUND) {
      return
    }
    trace.span {
      serviceName expectedServiceName()
      operationName "restlet.request"
      resourceName "${capitalize(endpoint)}Resource.${endpoint.name().toLowerCase()}"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" ResourceDecorator.DECORATE.component()
        if (endpoint == EXCEPTION) {
          // Restlet wraps all Exception types with ResourceException
          "error.msg" { String tagErrorMsg ->
            return tagErrorMsg.startsWith("Internal Server Error")
          }
          "error.type" { it == null || it == ResourceException.name }
          "error.stack" { it == null || it instanceof String }
        }
        defaultTags()
      }
    }
  }

  private static class App extends Application {
    App() {
      setStatusService(new MyCustomStatusService())
    }

    @Override
    Restlet createInboundRoot() {
      Router router = new Router(getContext())
      router.attach("/exception", ExceptionResource)
      router.attach("/error-status", ErrorResource)
      router.attach("/forwarded", ForwardedResource)
      router.attach("/path/{id}/param", PathParamResource)
      router.attach("/query", QueryParamResource)
      router.attach("/redirect", RedirectResource)
      router.attach("/success", SuccessResource)
      return router
    }
  }

  private static class MyCustomStatusService extends StatusService {
    @Override
    Representation getRepresentation(Status status, Request request, Response response) {
      Throwable t = status.getThrowable()
      if (t != null) {
        return new StringRepresentation(t.getMessage())
      }
      return null
    }
  }
}
