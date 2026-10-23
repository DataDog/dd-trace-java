package test.servlet5

import jakarta.servlet.GenericServlet
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse

/** Not an {@code HttpServlet}, like {@code jakarta.faces.webapp.FacesServlet}. */
class GenericRumServlet extends GenericServlet {
  private final String mimeType

  GenericRumServlet(String mime) {
    this.mimeType = mime
  }

  @Override
  void service(ServletRequest req, ServletResponse resp) throws ServletException, IOException {
    try (def writer = resp.getWriter()) {
      resp.setContentType(mimeType)
      writer.println("\n" +
      "<!doctype html>\n" +
      "<html>\n" +
      "  <head>\n" +
      "    <title>This is the title of the webpage!</title>\n" +
      "  </head>\n" +
      "  <body>\n" +
      "    <p>This is an example paragraph.</p>\n" +
      "  </body>\n" +
      "</html>")
    }
  }
}

class GenericHtmlRumServlet extends GenericRumServlet {
  GenericHtmlRumServlet() {
    super("text/html")
  }
}

class GenericXmlRumServlet extends GenericRumServlet {
  GenericXmlRumServlet() {
    super("text/xml")
  }
}
