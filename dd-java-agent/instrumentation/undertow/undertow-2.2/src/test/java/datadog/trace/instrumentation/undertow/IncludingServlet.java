package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Includes to the success servlet through an internal {@code RequestDispatcher}. */
public class IncludingServlet extends HttpServlet {
  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException {
    req.getRequestDispatcher(SUCCESS.getPath()).include(req, resp);
  }
}
