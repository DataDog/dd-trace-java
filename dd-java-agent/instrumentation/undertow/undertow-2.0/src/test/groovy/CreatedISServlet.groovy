import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

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
