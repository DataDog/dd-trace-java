import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.tomcat.TomcatDecorator

import javax.servlet.Servlet

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN

abstract class AbstractServletTest<SERVER, CONTEXT> extends HttpServerTest<SERVER> {
  @Override
  URI buildAddress(int port) {
    if (dispatch) {
      return new URI("http://localhost:$port/$context/dispatch/")
    }

    return new URI("http://localhost:$port/$context/")
  }

  @Override
  String component() {
    return TomcatDecorator.TOMCAT_SERVER
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
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

  boolean isDispatch() {
    return false
  }

  abstract String getContext()

  abstract Class<Servlet> servlet()

  abstract void addServlet(CONTEXT context, String path, Class<Servlet> servlet)

  protected void setupServlets(CONTEXT context) {
    def servlet = servlet()
    ServerEndpoint.values().findAll { it != NOT_FOUND && it != UNKNOWN }.each {
      addServlet(context, it.path, servlet)
    }
  }
}
