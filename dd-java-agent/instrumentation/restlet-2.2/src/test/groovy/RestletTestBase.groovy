import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.restlet.ResourceDecorator
import org.restlet.Application
import org.restlet.Component
import org.restlet.Request
import org.restlet.Response
import org.restlet.Restlet
import org.restlet.Server
import org.restlet.data.Protocol
import org.restlet.data.Status
import org.restlet.representation.Representation
import org.restlet.representation.StringRepresentation
import org.restlet.resource.ResourceException
import org.restlet.routing.Filter
import org.restlet.routing.Router
import org.restlet.service.StatusService

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

abstract class RestletTestBase extends HttpServerTest<Component> {

  class RestletServer implements HttpServer {
    def port = 0
    Component restletComponent
    Server restletServer

    RestletServer(Filter headerFilter) {
      restletComponent = new Component()
      restletServer = restletComponent.getServers().add(Protocol.HTTP, 0)
      restletComponent.getDefaultHost().attachDefault(new App(headerFilter))
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
    return new RestletServer(createHeaderFilter())
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
    return operation()
  }

  @Override
  String operation() {
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

  @Override
  boolean hasDecodedResource() {
    return false
  }

  @Override
  boolean testBadUrl() {
    false
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    switch (endpoint) {
      case NOT_FOUND:
        return null
      case PATH_PARAM:
        return testPathParam()
      case QUERY_ENCODED_BOTH:
        return endpoint.rawPath
      default:
        return endpoint.path
    }
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    return [ (Tags.PEER_HOSTNAME): "localhost" ]
  }

  String capitalize(String word) {
    Character.toUpperCase(word.charAt(0)).toString() + word.substring(1)
  }

  String capitalize(ServerEndpoint endpoint) {
    def parts = endpoint.name().split('_')
    return parts.collect({ capitalize(it.toLowerCase()) }).join("")
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
          "error.message" { String tagErrorMsg ->
            return tagErrorMsg.startsWith("Internal Server Error")
          }
          "error.type" { it == null || it == ResourceException.name }
          "error.stack" { it == null || it instanceof String }
        }
        defaultTags()
      }
    }
  }

  protected abstract Filter createHeaderFilter()

  private static class App extends Application {
    private Filter headerFilter
    App(Filter headerFilter) {
      this.headerFilter = headerFilter
      setStatusService(new MyCustomStatusService())
    }

    @Override
    Restlet createInboundRoot() {
      Router router = new Router(getContext())
      router.attach(EXCEPTION.rawPath, ExceptionResource)
      router.attach(ERROR.rawPath, ErrorResource)
      router.attach(FORWARDED.rawPath, ForwardedResource)
      router.attach("/path/{id}/param", PathParamResource)
      router.attach(QUERY_ENCODED_BOTH.rawPath, QueryEncodedBothResource)
      router.attach(QUERY_ENCODED_QUERY.rawPath, QueryEncodedQueryResource)
      router.attach(QUERY_PARAM.rawPath, QueryParamResource)
      router.attach(REDIRECT.rawPath, RedirectResource)
      router.attach(SUCCESS.rawPath, SuccessResource)
      headerFilter.setNext(router)
      return headerFilter
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
