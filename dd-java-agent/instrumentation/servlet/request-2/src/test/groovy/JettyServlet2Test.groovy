import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.servlet.ServletContextHandler

import javax.servlet.http.HttpServletRequest

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.MATRIX_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN

abstract class JettyServlet2Test extends HttpServerTest<Server> {

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

      HttpServerTest.ServerEndpoint.values().findAll { !(it in [NOT_FOUND, UNKNOWN, MATRIX_PARAM]) }.each {
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
    return operation()
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean testNotFound() {
    false
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean testSessionId() {
    // TODO enable when session id management is added to Jetty
    false
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path, "servlet.context": "/$CONTEXT"]
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

class JettyServlet2V0ForkedTest extends JettyServlet2Test implements TestingGenericHttpNamingConventions.ServerV0 {

}

class JettyServlet2V1ForkedTest extends JettyServlet2Test implements TestingGenericHttpNamingConventions.ServerV1 {

}

class IastJettyServlet2ForkedTest extends JettyServlet2Test implements TestingGenericHttpNamingConventions.ServerV0 {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test no calls if no modules registered'() {
    given:
    final appModule = Mock(ApplicationModule)
    def request = request(SUCCESS, "GET", null).build()

    when:
    client.newCall(request).execute()

    then:
    0 * appModule.onRealPath(_)
    0 * _
  }

  void 'test that iast modules are called'() {
    given:
    final appModule = Mock(ApplicationModule)
    InstrumentationBridge.registerIastModule(appModule)
    def request = request(SUCCESS, "GET", null).build()

    when:
    client.newCall(request).execute()

    then:
    1 *  appModule.onRealPath(_)
    0 * _

    when:
    client.newCall(request).execute()

    then: //Only call once per application context
    0 *  appModule.onRealPath(_)
    0 * _
  }

}
