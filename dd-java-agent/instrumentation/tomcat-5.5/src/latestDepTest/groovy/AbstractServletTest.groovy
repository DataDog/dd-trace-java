import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.tomcat.TomcatDecorator
import jakarta.servlet.Servlet

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN

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
    ServerEndpoint.values().findAll { it != NOT_FOUND && it != UNKNOWN }.each {
      addServlet(context, it.path, servlet)
    }
  }
}
