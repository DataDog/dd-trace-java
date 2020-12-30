import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.servlet3.AsyncDispatcherDecorator
import datadog.trace.instrumentation.servlet3.Servlet3Decorator
import okhttp3.Request
import okhttp3.RequestBody

import javax.servlet.Servlet

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.AUTH_REQUIRED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR

abstract class AbstractServlet3Test<SERVER, CONTEXT> extends HttpServerTest<SERVER> {
  @Override
  URI buildAddress() {
    if (dispatch) {
      return new URI("http://localhost:$port/$context/dispatch/")
    }

    return new URI("http://localhost:$port/$context/")
  }

  @Override
  String component() {
    return Servlet3Decorator.DECORATE.component()
  }

  @Override
  String expectedServiceName() {
    context
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  boolean hasHandlerSpan() {
    return isDispatch()
  }

  boolean isDispatch() {
    return false
  }

  // FIXME: Add authentication tests back in...
//  @Shared
//  protected String user = "user"
//  @Shared
//  protected String pass = "password"

  abstract String getContext()

  Class<Servlet> servlet = servlet()

  abstract Class<Servlet> servlet()

  abstract void addServlet(CONTEXT context, String path, Class<Servlet> servlet)

  protected void setupServlets(CONTEXT context) {
    def servlet = servlet()

    addServlet(context, SUCCESS.path, servlet)
    addServlet(context, QUERY_PARAM.path, servlet)
    addServlet(context, ERROR.path, servlet)
    addServlet(context, EXCEPTION.path, servlet)
    addServlet(context, REDIRECT.path, servlet)
    addServlet(context, AUTH_REQUIRED.path, servlet)
    addServlet(context, TIMEOUT.path, servlet)
    addServlet(context, TIMEOUT_ERROR.path, servlet)
  }

  protected void setupDispatchServlets(CONTEXT context, Class<Servlet> dispatchServlet) {
    addServlet(context, "/dispatch" + SUCCESS.path, dispatchServlet)
    addServlet(context, "/dispatch" + QUERY_PARAM.path, dispatchServlet)
    addServlet(context, "/dispatch" + REDIRECT.path, dispatchServlet)
    addServlet(context, "/dispatch" + ERROR.path, dispatchServlet)
    addServlet(context, "/dispatch" + EXCEPTION.path, dispatchServlet)
    addServlet(context, "/dispatch" + AUTH_REQUIRED.path, dispatchServlet)
    addServlet(context, "/dispatch" + TIMEOUT.path, dispatchServlet)
    addServlet(context, "/dispatch" + TIMEOUT_ERROR.path, dispatchServlet)

    // NOT_FOUND will hit on the initial URL, but be dispatched to a missing url
    addServlet(context, "/dispatch" + NOT_FOUND.path, dispatchServlet)
  }

  protected ServerEndpoint lastRequest

  @Override
  Request.Builder request(ServerEndpoint uri, String method, RequestBody body) {
    lastRequest = uri
    super.request(uri, method, body)
  }

  void dispatchSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "servlet.dispatch"
      resourceName endpoint.path
      errored endpoint.errored
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" AsyncDispatcherDecorator.DECORATE.component()
        if (endpoint.status > 0) {
          "$Tags.HTTP_STATUS" endpoint.status
        } else {
          "timeout" 1_000
        }
        if (context) {
          "servlet.context" "/$context"
        }
        "servlet.path" "/dispatch$endpoint.path"
        if (endpoint.errored) {
          "error.msg" { it == null || it == EXCEPTION.body }
          "error.type" { it == null || it == Exception.name }
          "error.stack" { it == null || it instanceof String }
        }
        defaultTags()
      }
    }
  }

  void includeSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "servlet.include"
      resourceName endpoint.status == 404 ? "404" : "$endpoint.path".replace("/dispatch", "")
      spanType DDSpanTypes.HTTP_SERVER
      // Exceptions are always bubbled up, other statuses aren't
      errored endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" "java-web-servlet-dispatcher"

        // Dispatcher.include doesn't bubble the status of the included
        "$Tags.HTTP_STATUS" Integer

        if (endpoint.errored) {
          "error.msg" { it == null || it == EXCEPTION.body }
          "error.type" { it == null || it == Exception.name }
          "error.stack" { it == null || it instanceof String }
        }
        defaultTags()
      }
    }
  }

  void forwardSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "servlet.forward"
      resourceName endpoint.status == 404 ? "404" : "$endpoint.path".replace("/dispatch", "")
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint.errored
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" "java-web-servlet-dispatcher"

        if (endpoint.status > 0) {
          "$Tags.HTTP_STATUS" endpoint.status
        }

        if (endpoint.errored) {
          "error.msg" { it == null || it == EXCEPTION.body }
          "error.type" { it == null || it == Exception.name }
          "error.stack" { it == null || it instanceof String }
        }
        defaultTags()
      }
    }
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == ERROR || endpoint == NOT_FOUND
  }

  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    if (endpoint == REDIRECT) {
      trace.span {
        operationName "servlet.response"
        resourceName "HttpServletResponse.sendRedirect"
        childOfPrevious()
        tags {
          "component" "java-web-servlet-response"
          defaultTags()
        }
      }
    } else if (endpoint == ERROR || endpoint == NOT_FOUND) {
      trace.span {
        operationName "servlet.response"
        resourceName "HttpServletResponse.sendError"
        childOfPrevious()
        tags {
          "component" "java-web-servlet-response"
          defaultTags()
        }
      }
    } else {
      throw new UnsupportedOperationException("responseSpan not implemented for " + endpoint)
    }
  }

  @Override
  void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    def dispatch = isDispatch()
    def bubblesResponse = bubblesResponse()
    trace.span {
      serviceName expectedServiceName()
      operationName expectedOperationName()
      resourceName endpoint.status == 404 ? "404" : "$method ${endpoint.resolve(address).path}"
      spanType DDSpanTypes.HTTP_SERVER
      // Exceptions are always bubbled up, other statuses: only if bubblesResponse == true
      errored((endpoint.errored && bubblesResponse) || endpoint == EXCEPTION)
      if (parentID != null) {
        traceId traceID
        parentId parentID
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.PEER_PORT" Integer
        "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
        "$Tags.HTTP_METHOD" method
        if (endpoint.status > 0) {
          "$Tags.HTTP_STATUS" { it == endpoint.status || !bubblesResponse }
        } else {
          "timeout" 1_000
        }
        if (context) {
          "servlet.context" "/$context"
        }

        if (dispatch) {
          "servlet.path" "/dispatch$endpoint.path"
        } else {
          "servlet.path" endpoint.path
        }

        if (endpoint.errored) {
          "error.msg" { it == null || it == EXCEPTION.body }
          "error.type" { it == null || it == Exception.name }
          "error.stack" { it == null || it instanceof String }
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" endpoint.query
        }
        defaultTags(true)
      }
    }
  }
}
