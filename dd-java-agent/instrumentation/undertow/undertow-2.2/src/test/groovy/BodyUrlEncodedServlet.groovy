import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.controller

class BodyUrlEncodedServlet extends HttpServlet {
  @Override
  protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
    controller(BODY_URLENCODED) {
      resp.status = BODY_URLENCODED.status
      resp.writer.print(
        req.parameterMap
        .findAll{
          it.key != 'ignore'
        }
        .collectEntries {[it.key, it.value as List]} as String)
    }
  }
}
