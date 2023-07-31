import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import datadog.trace.agent.test.base.HttpServerTest

import static datadog.trace.agent.test.base.HttpServerTest.controller
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH

class QueryEncodedBothServlet extends HttpServlet {
  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
    controller(QUERY_ENCODED_BOTH) {
      resp.addHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
      def queryString = req.queryString ?: req.getAttribute('javax.servlet.async.query_string')
      resp.writer.print(QUERY_ENCODED_BOTH.bodyForQuery(queryString))
    }
  }
}
