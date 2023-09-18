import datadog.trace.agent.test.base.HttpServer
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.MultipartConfigElement
import jakarta.servlet.Servlet
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.servlet.FilterMapping
import org.eclipse.jetty.servlet.ServletContextHandler

class JettyServer implements HttpServer {
  def port = 0
  final server = new Server(0) // select random open port

  JettyServer(Handler handler) {
    server.handler = handler
    server.addBean(errorHandler)
  }

  @Override
  void start() {
    server.start()
    port = server.connectors[0].localPort
    assert port > 0
  }

  @Override
  void stop() {
    server.stop()
  }

  @Override
  URI address() {
    return new URI("http://localhost:$port/context-path/")
  }

  @Override
  String toString() {
    return this.class.name
  }

  static AbstractHandler servletHandler(Class<? extends Servlet> servlet) {
    ServletContextHandler handler = new ServletContextHandler(null, "/context-path")
    handler.errorHandler = errorHandler
    handler.servletHandler.addFilterWithMapping(EnableMultipartFilter, '/*', FilterMapping.ALL)
    handler.servletHandler.addServletWithMapping(servlet, '/*')
    handler
  }

  static class EnableMultipartFilter implements Filter {
    static final MultipartConfigElement MULTIPART_CONFIG_ELEMENT = new MultipartConfigElement(System.getProperty('java.io.tmpdir'))
    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
      request.setAttribute('org.eclipse.jetty.multipartConfig', MULTIPART_CONFIG_ELEMENT)
      chain.doFilter(request, response)
    }
  }

  static errorHandler = new ErrorHandler() {
    @Override
    protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
      Throwable th = (Throwable) request.getAttribute("jakarta.servlet.error.exception")
      message = th ? th.message : message
      if (message) {
        writer.write(message)
      }
    }
  }
}
