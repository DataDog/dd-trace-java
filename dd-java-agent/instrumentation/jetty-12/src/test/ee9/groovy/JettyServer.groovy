import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import jakarta.servlet.Servlet
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import org.eclipse.jetty.ee9.nested.ErrorHandler
import org.eclipse.jetty.ee9.servlet.ServletContextHandler
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Server

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

  static Handler servletHandler(Class<? extends Servlet> servlet) {
    // defaults to jakarta servlet
    ServletContextHandler handler = new ServletContextHandler(null, "/context-path")
    handler.errorHandler = errorHandler
    HttpServerTest.ServerEndpoint.values()
      .findAll { !(it in [NOT_FOUND, UNKNOWN]) }
      .each {
        handler.servletHandler.addServletWithMapping(servlet, it.path)
      }
    handler.get()
  }

  static errorHandler = new ErrorHandler() {
    @Override
    protected void writeErrorPage(HttpServletRequest request, Writer writer, int code,
      String message, boolean showStacks) throws IOException {
      Throwable th = (Throwable) request.getAttribute("jakarta.servlet.error.exception")
      message = th == null ? message : th instanceof ServletException ? th.getRootCause().message : th.message
      if (message) {
        writer.write(message)
      }
    }
  }
}
