import datadog.trace.agent.test.base.HttpServerTest

import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class TestServlet extends HttpServlet {

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
        case FORWARDED:
          resp.status = endpoint.status
          resp.writer.print(req.getHeader("x-forwarded-for")) // Earliest version doesn't have RemoteIpValve
          break
        case QUERY_PARAM:
        case QUERY_ENCODED_QUERY:
        case QUERY_ENCODED_BOTH:
          resp.status = endpoint.status
          resp.writer.print(endpoint.bodyForQuery(req.queryString))
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
