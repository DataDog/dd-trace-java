import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import jakarta.servlet.http.HttpServlet
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.ErrorHandler

import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.servlet.ServletContextHandler

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.*

class Jetty11Test extends HttpServerTest<Server> {

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
    handler.getServletHandler().addServletWithMapping(TestServlet, "/*")
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
    return "servlet.request"
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
  boolean testBlocking() {
    true
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  static class TestServlet extends HttpServlet {
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
      System.err.println("CALLED $request.requestURI")
      String path = request.requestURI.substring(request.getContextPath().length())

      System.err.println("parsed $path")
      HttpServerTest.ServerEndpoint endpoint = forPath(path)
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
          case BODY_URLENCODED:
            response.status = endpoint.status
            response.writer.print(
              request.parameterMap
              .findAll {
                it.key != 'ignore'
              }
              .collectEntries { [it.key, it.value as List] } as String)
            break
          case QUERY_ENCODED_BOTH:
          case QUERY_ENCODED_QUERY:
          case QUERY_PARAM:
            response.status = endpoint.status
            response.writer.print(endpoint.bodyForQuery(request.queryString))
            break
          case USER_BLOCK:
            Blocking.forUser('user-to-block').blockIfMatch()
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
  }
}
