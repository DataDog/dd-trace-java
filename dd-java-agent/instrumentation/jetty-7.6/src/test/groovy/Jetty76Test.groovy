import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.ErrorHandler

import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class Jetty76Test extends HttpServerTest<Server> {

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
      return new URI("http://localhost:$port/")
    }

    @Override
    String toString() {
      return this.class.name
    }
  }

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
  HttpServer server() {
    return new JettyServer()
  }

  AbstractHandler handler() {
    TestHandler.INSTANCE
  }

  @Override
  String component() {
    return "jetty-server"
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  static void handleRequest(Request request, HttpServletResponse response) {
    ServerEndpoint endpoint = ServerEndpoint.forPath(request.requestURI)
    controller(endpoint) {
      response.contentType = "text/plain"
      response.addHeader(IG_RESPONSE_HEADER, IG_RESPONSE_HEADER_VALUE)
      switch (endpoint) {
        case SUCCESS:
          response.status = endpoint.status
          response.writer.print(endpoint.body)
          break
        case FORWARDED:
          response.status = endpoint.status
          response.writer.print(request.getHeader("x-forwarded-for"))
          break
        case QUERY_ENCODED_BOTH:
        case QUERY_ENCODED_QUERY:
        case QUERY_PARAM:
          response.status = endpoint.status
          response.writer.print(endpoint.bodyForQuery(request.queryString))
          break
        case REDIRECT:
          response.sendRedirect(endpoint.body)
          break
        case ERROR:
          response.sendError(endpoint.status, endpoint.body)
          break
        case EXCEPTION:
          throw new Exception(endpoint.body)
        default:
          response.status = NOT_FOUND.status
          response.writer.print(NOT_FOUND.body)
          break
      }
    }
  }

  static class TestHandler extends AbstractHandler {
    static final TestHandler INSTANCE = new TestHandler()

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      if (baseRequest.dispatcherType.name() != "ERROR") {
        handleRequest(baseRequest, response)
        baseRequest.handled = true
      } else {
        errorHandler.handle(target, baseRequest, response, response)
      }
    }
  }
}
