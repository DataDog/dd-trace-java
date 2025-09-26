import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.IgnoreIf

import javax.servlet.Servlet

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.MATRIX_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN

abstract class AbstractServlet3Test<SERVER, CONTEXT> extends HttpServerTest<SERVER> implements TestingGenericHttpNamingConventions.ServerV0 {
  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testRequestBodyISVariant() {
    true
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  URI buildAddress(int port) {
    if (dispatch) {
      return new URI("http://localhost:$port/$context/dispatch/")
    }

    return new URI("http://localhost:$port/$context/")
  }

  @Override
  String expectedServiceName() {
    context
  }

  @Override
  String expectedOperationName() {
    return operation()
  }

  boolean hasHandlerSpan() {
    return isDispatch()
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    Map<String, Serializable> map = ["servlet.path": dispatch ? "/dispatch$endpoint.path" : endpoint.path]
    if (context) {
      map.put("servlet.context", "/$context")
    }
    map
  }

  @Override
  boolean expectedErrored(ServerEndpoint endpoint) {
    (endpoint.errored && bubblesResponse()) || [EXCEPTION, CUSTOM_EXCEPTION, TIMEOUT_ERROR].contains(endpoint)
  }

  @Override
  Serializable expectedStatus(ServerEndpoint endpoint) {
    return { !bubblesResponse() || it == endpoint.status }
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
    ServerEndpoint.values()
      .findAll { !(it in [NOT_FOUND, UNKNOWN, MATRIX_PARAM]) }
      .each {
        addServlet(context, it.path, servlet)
      }
  }

  protected void setupDispatchServlets(CONTEXT context, Class<? extends Servlet> dispatchServlet) {
    ServerEndpoint.values()
      .findAll { !(it in [NOT_FOUND, UNKNOWN, MATRIX_PARAM]) }
      .each {
        addServlet(context, "/dispatch" + it.path, dispatchServlet)
      }

    // NOT_FOUND will hit on the initial URL, but be dispatched to a missing url
    addServlet(context, "/dispatch" + NOT_FOUND.path, dispatchServlet)
  }

  protected ServerEndpoint lastRequest

  @Override
  Request.Builder request(ServerEndpoint uri, String method, RequestBody body) {
    lastRequest = uri
    super.request(uri, method, body)
  }

  @IgnoreIf({ !instance.testException() })
  def "test exception with custom status"() {
    setup:
    def request = request(CUSTOM_EXCEPTION, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == CUSTOM_EXCEPTION.status
    if (testExceptionBody()) {
      assert response.body().string() == CUSTOM_EXCEPTION.body
    }

    and:
    assertTraces(1) {
      trace(spanCount(CUSTOM_EXCEPTION)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, CUSTOM_EXCEPTION)
        if (hasHandlerSpan()) {
          handlerSpan(it, CUSTOM_EXCEPTION)
        }
        controllerSpan(it, CUSTOM_EXCEPTION)
        if (hasResponseSpan(CUSTOM_EXCEPTION)) {
          responseSpan(it, CUSTOM_EXCEPTION)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  void includeSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "servlet.include"
      resourceName endpoint.status == 404 ? "404" : "$endpoint.path".replace("/dispatch", "")
      spanType DDSpanTypes.HTTP_SERVER
      // Exceptions are always bubbled up, other statuses aren't
      errored endpoint == EXCEPTION || endpoint == CUSTOM_EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" "java-web-servlet-dispatcher"
        if (context) {
          "servlet.context" "/$context"
        }
        "servlet.path" "/dispatch$endpoint.path"

        if (endpoint.throwsException) {
          "error.message" endpoint.body
          "error.type" { it == Exception.name || it == InputMismatchException.name }
          "error.stack" String
        }
        if ({ isDataStreamsEnabled() }){
          "$DDTags.PATHWAY_HASH" { String }
        }
        defaultTags()
      }
    }
  }

  void forwardSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "servlet.forward"
      resourceName "$endpoint.path".replace("/dispatch", "")
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint.throwsException
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" "java-web-servlet-dispatcher"

        if (context) {
          "servlet.context" "/$context"
        }
        if (dispatch) {
          "servlet.path" "/dispatch$endpoint.path"
        } else {
          "servlet.path" endpoint.path
        }

        if (endpoint.throwsException) {
          "error.message" endpoint.body
          "error.type" { it == Exception.name || it == InputMismatchException.name }
          "error.stack" String
        }
        if ({ isDataStreamsEnabled() }){
          "$DDTags.PATHWAY_HASH" { String }
        }
        defaultTags()
      }
    }
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return [REDIRECT, ERROR, NOT_FOUND].contains(endpoint)
  }

  boolean isRespSpanChildOfDispatchOnException() {
    false
  }

  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    String method
    switch (endpoint) {
      case REDIRECT:
        method = "sendRedirect"
        break
      case ERROR:
      case NOT_FOUND:
      case EXCEPTION:
      case CUSTOM_EXCEPTION:
        method = "sendError"
        break
      default:
        throw new UnsupportedOperationException("responseSpan not implemented for " + endpoint)
    }
    trace.span {
      operationName "servlet.response"
      resourceName "HttpServletResponse.$method"
      if (endpoint.throwsException) {
        // Not a child of the controller because sendError called by framework
        if (dispatch && respSpanChildOfDispatchOnException) {
          // on jetty the response span is started around the scope of dispatch span
          // (because we instrument on a lower level on jetty, not just around the servlet)
          childOf(trace.span(1))
        } else {
          childOf(trace.span(0))
        }
      } else {
        childOfPrevious()
      }
      tags {
        "component" "java-web-servlet-response"
        defaultTags()
      }
    }
  }
}
