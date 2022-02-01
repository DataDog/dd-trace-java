import datadog.trace.agent.test.base.HttpServerTest
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED_IS
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
      resp.addHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
      switch (endpoint) {
        case SUCCESS:
          resp.status = endpoint.status
          resp.writer.print(endpoint.body)
          break
        case FORWARDED:
          resp.status = endpoint.status
          resp.writer.print(req.remoteAddr)
          break
        case CREATED:
          resp.status = endpoint.status
          resp.writer.print("${endpoint.body}: ${req.reader.text}")
          break
        case CREATED_IS:
          resp.status = endpoint.status
          def stream = req.inputStream
          resp.writer.print("${endpoint.body}: ${stream.getText('UTF-8')}")
          break
        case BODY_URLENCODED:
          resp.status = endpoint.status
          resp.writer.print(
            req.parameterMap.collectEntries {[it.key, it.value as List]} as String)
          break
        case QUERY_ENCODED_BOTH:
        case QUERY_ENCODED_QUERY:
        case QUERY_PARAM:
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
