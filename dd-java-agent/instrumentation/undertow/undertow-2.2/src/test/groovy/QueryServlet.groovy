import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.controller
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM

class QueryServlet extends HttpServlet {
  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
    controller(QUERY_PARAM) {
      resp.writer.print(QUERY_PARAM.bodyForQuery(req.queryString))
    }
  }
}
