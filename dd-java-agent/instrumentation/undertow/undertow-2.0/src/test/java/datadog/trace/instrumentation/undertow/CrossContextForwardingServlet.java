package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Forwards to the success servlet of another deployment, through its servlet context. */
public class CrossContextForwardingServlet extends HttpServlet {
  /** The context path of the deployment this servlet forwards into. */
  public static final String OTHER_CONTEXT = "/other";

  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException {
    getServletContext()
        .getContext(OTHER_CONTEXT)
        .getRequestDispatcher(SUCCESS.getPath())
        .forward(req, resp);
  }
}
