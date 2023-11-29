import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.controller

class BodyMultipartServlet extends HttpServlet {
  @Override
  protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
    controller(BODY_MULTIPART) {
      resp.status = BODY_MULTIPART.status
      resp.writer.print(
        req.parameterMap
        .findAll{
          it.key != 'ignore'
        }
        .collectEntries {[it.key, it.value as List]} as String)
    }
  }
}
