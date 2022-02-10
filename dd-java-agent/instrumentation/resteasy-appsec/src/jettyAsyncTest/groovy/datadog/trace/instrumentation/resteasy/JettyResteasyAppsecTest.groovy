package datadog.trace.instrumentation.resteasy

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.jboss.resteasy.plugins.server.servlet.HttpServlet30Dispatcher
import spock.lang.Shared

import javax.servlet.http.HttpServletRequest

class JettyResteasyAppsecTest extends AbstractResteasyAppsecTest {
  @Shared
  Server server

  static errorHandler = new ErrorHandler() {
    @Override
    protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
      Throwable th = (Throwable) request.getAttribute("javax.servlet.error.exception")
      message = th ? th.message : message
      if (message) {
        writer.write(message)
      }
    }
  }

  @Override
  void startServer() {
    server = new Server(0)
    server.addBean(errorHandler)

    def contextHandler = new ServletContextHandler()
    def holder = new ServletHolder(HttpServlet30Dispatcher)
    holder.setInitParameter('javax.ws.rs.Application', TestJaxRsApplication.name)
    contextHandler.addServlet(holder, '/*')
    server.handler = contextHandler
    server.start()
    address = new URI("http://localhost:${server.connectors[0].localPort}/")
  }

  @Override
  void stopServer() {
    server.stop()
  }
}
