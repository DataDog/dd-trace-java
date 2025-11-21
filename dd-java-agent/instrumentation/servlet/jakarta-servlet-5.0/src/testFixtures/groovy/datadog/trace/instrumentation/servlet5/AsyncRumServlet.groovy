package datadog.trace.instrumentation.servlet5

import jakarta.servlet.AsyncContext
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

class AsyncRumServlet extends HttpServlet {
  private final String mimeType

  AsyncRumServlet(String mime) {
    this.mimeType = mime
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    // write a partial content
    resp.getWriter().println("\n" +
      "<!doctype html>")
    // finish it later
    final AsyncContext asyncContext = req.startAsync()
    final String mime = mimeType
    new Timer().schedule(new TimerTask() {
        @Override
        void run() {
          def writer = asyncContext.getResponse().getWriter()
          try {
            asyncContext.getResponse().setContentType(mime)
            writer.println(
              "<html>\n" +
              "  <head>\n" +
              "    <title>This is the title of the webpage!</title>\n" +
              "  </head>\n" +
              "  <body>\n" +
              "    <p>This is an example paragraph. Anything in the <strong>body</strong> tag will appear on the page, just like this <strong>p</strong> tag and its contents.</p>\n" +
              "  </body>\n" +
              "</html>")
          } finally {
            asyncContext.complete()
          }
        }
      }, 2000)
  }
}

class HtmlAsyncRumServlet extends AsyncRumServlet {
  HtmlAsyncRumServlet() {
    super("text/html")
  }
}

class XmlAsyncRumServlet extends AsyncRumServlet {
  XmlAsyncRumServlet() {
    super("text/xml")
  }
}
