package datadog.trace.instrumentation.jetty9

import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.servlet3.TestServlet3
import groovy.transform.CompileStatic
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.ErrorHandler

import javax.servlet.DispatcherType
import javax.servlet.MultipartConfigElement
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class TestHandler extends AbstractHandler {
  static final TestHandler INSTANCE = new TestHandler()
  private static final MultipartConfigElement MULTIPART_CONFIG_ELEMENT = new MultipartConfigElement(System.getProperty('java.io.tmpdir'))

  final TestServlet3.Sync testServlet3 = new TestServlet3.Sync() {
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
    INSTANCE.testServlet3.service(request, response)
  }

  @Override
  void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    // name of attribute varies depending on the jetty version
    request.setAttribute('org.eclipse.jetty.multipartConfig', MULTIPART_CONFIG_ELEMENT)
    request.setAttribute('org.eclipse.multipartConfig', MULTIPART_CONFIG_ELEMENT)
    if (baseRequest.dispatcherType != DispatcherType.ERROR) {
      handleRequest(baseRequest, response)
      baseRequest.handled = true
    } else {
      errorHandler.handle(target, baseRequest, response, response)
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
}
