package datadog.trace.instrumentation.servlet3

import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class RumServlet extends HttpServlet {
  private final String mimeType

  RumServlet(String mime) {
    this.mimeType = mime
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    try (def writer = resp.getWriter()) {
      resp.setContentType(mimeType)
      writer.println("\n" +
      "<!doctype html>\n" +
      "<html>\n" +
      "  <head>\n" +
      "    <title>This is the title of the webpage!</title>\n" +
      "  </head>\n" +
      "  <body>\n" +
      "    <p>This is an example paragraph. Anything in the <strong>body</strong> tag will appear on the page, just like this <strong>p</strong> tag and its contents.</p>\n" +
      "  </body>\n" +
      "</html>")
    }
  }
}

class HtmlRumServlet extends RumServlet {
  HtmlRumServlet() {
    super("text/html")
  }
}

class XmlRumServlet extends RumServlet {
  XmlRumServlet() {
    super("text/xml")
  }
}
