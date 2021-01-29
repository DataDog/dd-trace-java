import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.tomcat.TomcatDecorator

import javax.servlet.Servlet

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.AUTH_REQUIRED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR

abstract class AbstractServletTest<SERVER, CONTEXT> extends HttpServerTest<SERVER> {
  @Override
  URI buildAddress() {
    if (dispatch) {
      return new URI("http://localhost:$port/$context/dispatch/")
    }

    return new URI("http://localhost:$port/$context/")
  }

  @Override
  String component() {
    return TomcatDecorator.DECORATE.component()
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

  abstract String getContext()

  abstract Class<Servlet> servlet()

  abstract void addServlet(CONTEXT context, String path, Class<Servlet> servlet)

  protected void setupServlets(CONTEXT context) {
    def servlet = servlet()

    addServlet(context, SUCCESS.path, servlet)
    addServlet(context, QUERY_PARAM.path, servlet)
    addServlet(context, ERROR.path, servlet)
    addServlet(context, EXCEPTION.path, servlet)
    addServlet(context, CUSTOM_EXCEPTION.path, servlet)
    addServlet(context, REDIRECT.path, servlet)
    addServlet(context, AUTH_REQUIRED.path, servlet)
    addServlet(context, TIMEOUT.path, servlet)
    addServlet(context, TIMEOUT_ERROR.path, servlet)
  }
}
