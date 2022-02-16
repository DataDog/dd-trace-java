import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static datadog.trace.agent.test.base.HttpServerTest.controller
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT

class RedirectServlet extends HttpServlet {
    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
      controller(REDIRECT) {
        // resp.setStatus(REDIRECT.status)
        // resp.addHeader("Location", REDIRECT.body)
        // TODO CRG use sendRedirect. Currently there is something wrong with the checkpointer
        resp.sendRedirect(REDIRECT.body)
      }
    }
}
