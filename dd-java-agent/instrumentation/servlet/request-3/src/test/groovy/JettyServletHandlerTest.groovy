import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.jetty9.JettyDecorator
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.servlet.ServletHandler

import javax.servlet.Servlet
import javax.servlet.http.HttpServletRequest

import static JettyServlet3Test.IS_LATEST
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR

class JettyServletHandlerTest extends AbstractServlet3Test<Server, ServletHandler> {

  @Override
  Server startServer(int port) {
    Server server = new Server(port)
    ServletHandler handler = new ServletHandler()
    server.setHandler(handler)
    setupServlets(handler)
    server.addBean(new ErrorHandler() {
      protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
        Throwable th = (Throwable) request.getAttribute("javax.servlet.error.exception")
        writer.write(th ? th.message : message)
      }
    })
    server.start()
    return server
  }

  @Override
  void addServlet(ServletHandler servletHandler, String path, Class<Servlet> servlet) {
    servletHandler.addServletWithMapping(servlet, path)
  }

  @Override
  void stopServer(Server server) {
    server.stop()
    server.destroy()
  }

  @Override
  String component() {
    return JettyDecorator.JETTY_SERVER
  }

  @Override
  String getContext() {
    ""
  }

  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }

  @Override
  String expectedServiceName() {
    CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
  }

  @Override
  void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    def dispatch = isDispatch()
    def bubblesResponse = bubblesResponse()
    trace.span {
      serviceName expectedServiceName()
      operationName expectedOperationName()
      resourceName endpoint.status == 404 ? "404" : "$method ${endpoint.resolve(address).path}"
      spanType DDSpanTypes.HTTP_SERVER
      // Exceptions are always bubbled up, other statuses: only if bubblesResponse == true
      errored((endpoint.errored && bubblesResponse) || endpoint == EXCEPTION || endpoint == TIMEOUT_ERROR)
      if (parentID != null) {
        traceId traceID
        parentId parentID
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.PEER_PORT" Integer
        "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_STATUS" { it == endpoint.status || !bubblesResponse }
        if (context) {
          "servlet.context" "/$context"
        }

        if (dispatch) {
          "servlet.path" "/dispatch$endpoint.path"
        } else {
          "servlet.path" endpoint.path
        }

        if (endpoint.errored) {
          "error.msg" { it == null || it == EXCEPTION.body }
          "error.type" { it == null || it == Exception.name }
          "error.stack" { it == null || it instanceof String }
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" endpoint.query
        }
        defaultTags(true)
      }
    }
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == ERROR || (endpoint == EXCEPTION && !IS_LATEST) || endpoint == NOT_FOUND
  }

  @Override
  boolean testNotFound() {
    false
  }
}
