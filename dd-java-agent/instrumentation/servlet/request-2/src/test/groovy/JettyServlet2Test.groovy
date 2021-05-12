import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.servlet.ServletContextHandler

import javax.servlet.http.HttpServletRequest

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN

class JettyServlet2Test extends HttpServerTest<Server> {

  private static final CONTEXT = "ctx"

  class JettyServer implements HttpServer {
    def port = 0
    final server = new Server(0) // select random open port

    JettyServer() {
      server.connectors.each {
        it.setHost('localhost')
      }
      ServletContextHandler servletContext = new ServletContextHandler(null, "/$CONTEXT")
      servletContext.errorHandler = new ErrorHandler() {
          protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
            Throwable th = (Throwable) request.getAttribute("javax.servlet.error.exception")
            writer.write(th ? th.message : message)
          }
        }

      // FIXME: Add tests for security/authentication.
      //    ConstraintSecurityHandler security = setupAuthentication(jettyServer)
      //    servletContext.setSecurityHandler(security)

      HttpServerTest.ServerEndpoint.values().findAll { it != NOT_FOUND && it != UNKNOWN }.each {
        servletContext.addServlet(TestServlet2.Sync, it.path)
      }

      server.setHandler(servletContext)
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
      server.destroy()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/$CONTEXT/")
    }

    @Override
    String toString() {
      return this.class.name
    }
  }

  @Override
  HttpServer server() {
    return new JettyServer()
  }

  @Override
  String component() {
    return "jetty-server"
  }

  @Override
  String expectedServiceName() {
    CONTEXT
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  @Override
  boolean testNotFound() {
    false
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == NOT_FOUND || endpoint == EXCEPTION || endpoint == ERROR
  }

  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    String method
    switch (endpoint) {
      case REDIRECT:
        method = "sendRedirect"
        break
      case ERROR:
      case NOT_FOUND:
      case EXCEPTION:
      case CUSTOM_EXCEPTION:
        method = "sendError"
        break
      default:
        throw new UnsupportedOperationException("responseSpan not implemented for " + endpoint)
    }
    trace.span {
      operationName "servlet.response"
      resourceName "HttpServletResponse.$method"
      if (endpoint.throwsException) {
        childOf(trace.span(0)) // Not a child of the controller because sendError called by framework
      } else {
        childOfPrevious()
      }
      tags {
        "component" "java-web-servlet-response"
        defaultTags()
      }
    }
  }

  // parent span must be cast otherwise it breaks debugging classloading (junit loads it early)
  void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName expectedOperationName()
      resourceName endpoint.resource(method, address, testPathParam())
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint.errored
      if (parentID != null) {
        traceId traceID
        parentId parentID
      } else {
        parent()
      }
      statusCode endpoint.status
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" Integer
        "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
        "$Tags.HTTP_METHOD" method
        if (endpoint == FORWARDED) {
          "$Tags.HTTP_FORWARDED_IP" endpoint.body
        }
        "servlet.context" "/$CONTEXT"
        "servlet.path" endpoint.path
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

  /**
   * Setup simple authentication for tests
   * <p>
   *     requests to {@code /auth/*} need login 'user' and password 'password'
   * <p>
   *     For details @see <a href="http://www.eclipse.org/jetty/documentation/9.3.x/embedded-examples.html">http://www.eclipse.org/jetty/documentation/9.3.x/embedded-examples.html</a>
   *
   * @param jettyServer server to attach login service
   * @return SecurityHandler that can be assigned to servlet
   */
  //  private ConstraintSecurityHandler setupAuthentication(Server jettyServer) {
  //    ConstraintSecurityHandler security = new ConstraintSecurityHandler()
  //
  //    Constraint constraint = new Constraint()
  //    constraint.setName("auth")
  //    constraint.setAuthenticate(true)
  //    constraint.setRoles("role")
  //
  //    ConstraintMapping mapping = new ConstraintMapping()
  //    mapping.setPathSpec("/auth/*")
  //    mapping.setConstraint(constraint)
  //
  //    security.setConstraintMappings(mapping)
  //    security.setAuthenticator(new BasicAuthenticator())
  //
  //    LoginService loginService = new HashLoginService("TestRealm",
  //      "src/test/resources/realm.properties")
  //    security.setLoginService(loginService)
  //    jettyServer.addBean(loginService)
  //
  //    security
  //  }
}
