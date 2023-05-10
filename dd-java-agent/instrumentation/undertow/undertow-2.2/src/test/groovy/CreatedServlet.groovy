import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.controller

class CreatedServlet extends HttpServlet {
  @Override
  protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
    controller(CREATED) {
      resp.status = CREATED.status
      resp.writer.print("${CREATED.body}: ${req.reader.text}")
    }
  }
}
