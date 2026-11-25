package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Includes to the success servlet through an internal {@code RequestDispatcher}. */
public class IncludingServlet extends HttpServlet {
  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException {
    req.getRequestDispatcher(SUCCESS.getPath()).include(req, resp);
  }
}
