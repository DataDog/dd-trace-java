package test

import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.servlet3.TestServlet3
import groovy.transform.CompileStatic
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.server.session.SessionHandler
import org.eclipse.jetty.servlet.ServletContextHandler

import javax.servlet.DispatcherType
import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.FilterConfig
import javax.servlet.MultipartConfigElement
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class TestHandler extends ServletContextHandler {
  static final TestHandler INSTANCE = new TestHandler()
  private static final MultipartConfigElement MULTIPART_CONFIG_ELEMENT = new MultipartConfigElement(System.getProperty('java.io.tmpdir'))

  TestHandler() {
    setSessionHandler(new SessionHandler())
    setErrorHandler(new ErrorHandler() {
        @Override
        protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
          Throwable th = (Throwable) request.getAttribute("javax.servlet.error.exception")
          message = th ? th.message : message
          if (message) {
            writer.write(message)
          }
        }
      })
    addFilter(MultipartFilter, "/*", EnumSet.of(DispatcherType.REQUEST))
    addServlet(TestServlet, "/*")
  }

  static class MultipartFilter implements Filter {

    @Override
    void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
      request.setAttribute('org.eclipse.jetty.multipartConfig', MULTIPART_CONFIG_ELEMENT)
      request.setAttribute('org.eclipse.multipartConfig', MULTIPART_CONFIG_ELEMENT)
      filterChain.doFilter(request, response)
    }

    @Override
    void destroy() {
    }
  }

  static class TestServlet extends TestServlet3.Sync {
    @Override
    HttpServerTest.ServerEndpoint determineEndpoint(HttpServletRequest req) {
      HttpServerTest.ServerEndpoint.forPath(req.requestURI)
    }

    @Override
    void service(HttpServletRequest req, HttpServletResponse resp) {
      HttpServerTest.ServerEndpoint endpoint = determineEndpoint(req)
      if (endpoint == HttpServerTest.ServerEndpoint.BODY_MULTIPART) {
        // jetty 9.0 needs an explicit call to getParts() to process the form params
        req.parts
      }
      if (endpoint == HttpServerTest.ServerEndpoint.UNKNOWN || endpoint == HttpServerTest.ServerEndpoint.NOT_FOUND) {
        resp.status = HttpServerTest.ServerEndpoint.NOT_FOUND.status
        resp.writer.print(HttpServerTest.ServerEndpoint.NOT_FOUND.body)
      } else {
        super.service(req, resp)
      }
    }
  }

  @CompileStatic
  static void handleRequest(Request request, HttpServletResponse response) {
    new TestServlet().service(request, response)
  }
}
