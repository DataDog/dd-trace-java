import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import jakarta.servlet.http.HttpServletRequest
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.servlet.ServletContextHandler
import datadog.trace.instrumentation.servlet5.TestServlet5

abstract class Jetty11Test extends HttpServerTest<Server> {

  class JettyServer implements HttpServer {
    def port = 0
    final server = new Server(0) // select random open port

    JettyServer() {
      server.setHandler(handler())
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

  @Override
  HttpServer server() {
    return new JettyServer()
  }

  AbstractHandler handler() {
    ServletContextHandler handler = new ServletContextHandler(null, "/context-path")
    handler.setErrorHandler(Jetty11Test.errorHandler)
    handler.getServletHandler().addServletWithMapping(TestServlet5, "/*")
    return handler
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    return ["servlet.context": "/context-path", "servlet.path": endpoint.path]
  }

  @Override
  String component() {
    return "jetty-server"
  }

  @Override
  String expectedOperationName() {
    return operation()
  }

  @Override
  String expectedServiceName() {
    return "context-path"
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testRequestBodyISVariant() {
    true
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testUserBlocking() {
    true
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }
}

class Jetty11V0ForkedTest extends Jetty11Test implements TestingGenericHttpNamingConventions.ServerV0 {

}

class Jetty11V1ForkedTest extends Jetty11Test implements TestingGenericHttpNamingConventions.ServerV1 {

}
