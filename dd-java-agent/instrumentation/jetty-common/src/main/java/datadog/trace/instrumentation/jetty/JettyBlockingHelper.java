package datadog.trace.instrumentation.jetty;

import java.io.IOException;
import java.io.PrintWriter;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.Response;

public class JettyBlockingHelper {
  private JettyBlockingHelper() {}

  public static void block(Response response) {
    if (!response.isCommitted()) {
      PrintWriter writer;
      try {
        writer = response.getWriter();
        response.setStatus(403);
        response.setHeader("Content-type", "text/plain");
        writer.write("Access denied (request blocked)");
        writer.close();
      } catch (IOException | IllegalStateException e) {
      }
    }
    // throw this type of exception to avoid jetty calling response#sendError
    throw new RuntimeIOException("Interrupted request (blocking)");
  }
}
