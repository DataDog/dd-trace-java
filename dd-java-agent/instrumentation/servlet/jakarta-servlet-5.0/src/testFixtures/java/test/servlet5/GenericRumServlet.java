package test.servlet5;

import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * A servlet implementing {@code Servlet} without extending {@code HttpServlet}, like {@code
 * jakarta.faces.webapp.FacesServlet}.
 */
public abstract class GenericRumServlet extends GenericServlet {
  private final String mimeType;

  protected GenericRumServlet(final String mimeType) {
    this.mimeType = mimeType;
  }

  @Override
  public void service(final ServletRequest request, final ServletResponse response)
      throws ServletException, IOException {
    try (PrintWriter writer = response.getWriter()) {
      // the content type is set after the writer is retrieved on purpose
      response.setContentType(mimeType);
      writer.println(
          "\n"
              + "<!doctype html>\n"
              + "<html>\n"
              + "  <head>\n"
              + "    <title>This is the title of the webpage!</title>\n"
              + "  </head>\n"
              + "  <body>\n"
              + "    <p>This is an example paragraph.</p>\n"
              + "  </body>\n"
              + "</html>");
    }
  }
}
