import datadog.trace.agent.test.base.HttpServerTest
import groovy.servlet.AbstractHttpServlet

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class TestServlet extends AbstractHttpServlet {

  static HttpServerTest.ServerEndpoint getEndpoint(HttpServletRequest req) {
    // Most correct would be to get the dispatched path from the request
    // This is not part of the spec varies by implementation so the simplest is just removing
    // "/dispatch"
    String truePath = req.servletPath.replace("/dispatch", "")
    return HttpServerTest.ServerEndpoint.forPath(truePath)
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) {
    HttpServerTest.ServerEndpoint endpoint = getEndpoint(req)
    HttpServerTest.controller(endpoint) {
      resp.contentType = "text/plain"
      switch (endpoint) {
        case SUCCESS:
          resp.status = endpoint.status
          resp.writer.print(endpoint.body)
          break
        case QUERY_PARAM:
          resp.status = endpoint.status
          resp.writer.print(req.queryString)
          break
        case REDIRECT:
          resp.sendRedirect(endpoint.body)
          break
        case ERROR:
          resp.sendError(endpoint.status, endpoint.body)
          break
        case EXCEPTION:
          throw new Exception(endpoint.body)
        case CUSTOM_EXCEPTION:
          throw new InputMismatchException(endpoint.body)
      }
    }
  }
}
