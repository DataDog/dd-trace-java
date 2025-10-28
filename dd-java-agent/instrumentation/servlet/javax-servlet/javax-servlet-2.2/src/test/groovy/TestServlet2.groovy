import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import groovy.servlet.AbstractHttpServlet

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SESSION_ID
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK

class TestServlet2 {

  static class Sync extends AbstractHttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      req.getRequestDispatcher()
      HttpServerTest.ServerEndpoint endpoint = HttpServerTest.ServerEndpoint.forPath(req.servletPath)
      HttpServerTest.controller(endpoint) {
        resp.contentType = "text/plain"
        resp.addHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
        switch (endpoint) {
          case SUCCESS:
            resp.status = endpoint.status
            resp.writer.print(endpoint.body)
            break
          case CREATED:
            resp.status = endpoint.status
            resp.writer.print("${endpoint.body}: ${req.reader.text}")
            break
          case FORWARDED:
            resp.status = endpoint.status
            resp.writer.print(req.getHeader("x-forwarded-for"))
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
          case USER_BLOCK:
            Blocking.forUser('user-to-block').blockIfMatch()
            break
          case EXCEPTION:
            throw new Exception(endpoint.body)
          case SESSION_ID:
            req.getSession(true)
            resp.status = endpoint.status
            resp.writer.print(req.requestedSessionId)
            break
        }
      }
    }
  }
}
