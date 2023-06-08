import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED_IS
import static datadog.trace.agent.test.base.HttpServerTest.controller

class CreatedISServlet extends HttpServlet {
  @Override
  protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
    controller(CREATED_IS) {
      resp.status = CREATED_IS.status
      def stream = req.inputStream
      resp.writer.print("${CREATED_IS.body}: ${stream.getText('UTF-8')}")
    }
  }
}
