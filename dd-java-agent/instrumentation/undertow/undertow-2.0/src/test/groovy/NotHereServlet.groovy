import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_HERE
import static datadog.trace.agent.test.base.HttpServerTest.controller

class NotHereServlet extends HttpServlet {
  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
    controller(NOT_HERE) {
      resp.sendError(NOT_HERE.status)
    }
  }
}
