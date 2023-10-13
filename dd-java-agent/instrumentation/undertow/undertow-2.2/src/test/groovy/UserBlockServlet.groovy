import datadog.appsec.api.blocking.Blocking

import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK
import static datadog.trace.agent.test.base.HttpServerTest.controller

class UserBlockServlet extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    controller(USER_BLOCK) {
      Blocking.forUser('user-to-block').blockIfMatch()
      resp.writer.print('user not blocked')
    }
  }
}
