import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.servlet3.AsyncDispatcherDecorator
import datadog.trace.instrumentation.servlet3.HtmlRumServlet
import datadog.trace.instrumentation.servlet3.TestServlet3
import datadog.trace.instrumentation.servlet3.XmlRumServlet
import datadog.context.Context

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.server.session.SessionHandler
import org.eclipse.jetty.servlet.ServletContextHandler
import spock.lang.Retry

import javax.servlet.AsyncEvent
import javax.servlet.AsyncListener
import javax.servlet.Servlet
import javax.servlet.ServletException
import javax.servlet.annotation.WebServlet
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.instrumentation.servlet3.TestServlet3.SERVLET_TIMEOUT

abstract class JettyServlet3Test extends AbstractServlet3Test<Server, ServletContextHandler> {
  static final boolean IS_LATEST
  static {
    try {
      Class.forName("org.eclipse.jetty.server.HttpChannel")
      IS_LATEST = true
    } catch (ClassNotFoundException e) {
      IS_LATEST = false
    }
  }

  class JettyServer implements HttpServer {
    def port = 0
    final server = new Server(0) // select random open port

    JettyServer() {
      server.connectors.each {
        it.setHost('localhost')
      }

      ServletContextHandler servletContext = new ServletContextHandler(null, "/$context", ServletContextHandler.SESSIONS)
      servletContext.sessionHandler = new SessionHandler()
      servletContext.errorHandler = new ErrorHandler() {
          @Override
          void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
            try {
              // This allows calling response.sendError in async context on a different thread. (Without results in NPE.)
              def original = org.eclipse.jetty.server.AbstractHttpConnection.currentConnection
              org.eclipse.jetty.server.AbstractHttpConnection.setCurrentConnection(baseRequest.connection)
              super.handle(target, baseRequest, request, response)
              org.eclipse.jetty.server.AbstractHttpConnection.setCurrentConnection(original)
            } catch (Throwable e) {
              // latest dep fallback which is missing AbstractHttpConnection and doesn't need the special handling
              super.handle(target, baseRequest, request, response)
            }
          }

          protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
            Throwable t = (Throwable) request.getAttribute("javax.servlet.error.exception")
            def response = ((Request) request).response
            if (t) {
              if (t instanceof ServletException) {
                t = t.rootCause
              }
              if (t instanceof InputMismatchException) {
                response.status = CUSTOM_EXCEPTION.status
              }
              writer.write(t.message)
            } else {
              writer.write(message)
            }
          }
        }
      //    setupAuthentication(jettyServer, servletContext)
      setupServlets(servletContext)
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
      if (dispatch) {
        return new URI("http://localhost:$port/$context/dispatch/")
      }
      return new URI("http://localhost:$port/$context/")
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
  String getContext() {
    return "jetty-context"
  }

  @Override
  boolean testNotFound() {
    false
  }

  @Override
  boolean isRespSpanChildOfDispatchOnException() {
    true
  }

  @Override
  boolean testSessionId() {
    // TODO enable when session id management is added to Jetty
    false
  }

  @Override
  boolean hasResponseSpan(ServerEndpoint endpoint) {
    if (IS_LATEST) {
      return [NOT_FOUND, ERROR, REDIRECT].contains(endpoint)
    }
    return [NOT_FOUND, ERROR, EXCEPTION, CUSTOM_EXCEPTION, REDIRECT].contains(endpoint)
  }

  @Override
  void addServlet(ServletContextHandler servletContext, String path, Class<Servlet> servlet) {
    servletContext.addServlet(servlet, path)
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.throwsException) {
      ["error.message": "${endpoint.body}",
        "error.type"   : { it == Exception.name || it == InputMismatchException.name },
        "error.stack"  : String]
    } else {
      Collections.emptyMap()
    }
  }

  void dispatchSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "servlet.dispatch"
      resourceName endpoint.path
      errored(endpoint.throwsException || endpoint == TIMEOUT_ERROR)
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" AsyncDispatcherDecorator.DECORATE.component()
        if (endpoint == TIMEOUT || endpoint == TIMEOUT_ERROR) {
          "timeout" SERVLET_TIMEOUT
        }
        if (context) {
          "servlet.context" "/$context"
        }
        "servlet.path" "/dispatch$endpoint.path"
        if (endpoint.throwsException) {
          "error.message" endpoint.body
          "error.type" { it == Exception.name || it == InputMismatchException.name }
          "error.stack" String
        }
        defaultTags()
      }
    }
  }

  // FIXME: Add authentication tests back in...
  //  static setupAuthentication(Server jettyServer, ServletContextHandler servletContext) {
  //    ConstraintSecurityHandler authConfig = new ConstraintSecurityHandler()
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
  //    authConfig.setConstraintMappings(mapping)
  //    authConfig.setAuthenticator(new BasicAuthenticator())
  //
  //    LoginService loginService = new HashLoginService("TestRealm",
  //      "src/test/resources/realm.properties")
  //    authConfig.setLoginService(loginService)
  //    jettyServer.addBean(loginService)
  //
  //    servletContext.setSecurityHandler(authConfig)
  //  }
}

class JettyServlet3TestSync extends JettyServlet3Test {

  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }
}

class JettyServlet3SyncRumInjectionForkedTest extends JettyServlet3TestSync {
  @Override
  boolean testRumInjection() {
    true
  }

  @Override
  protected void setupServlets(ServletContextHandler servletContextHandler) {
    super.setupServlets(servletContextHandler)
    addServlet(servletContextHandler, "/gimme-html", HtmlRumServlet)
    addServlet(servletContextHandler, "/gimme-xml", XmlRumServlet)
  }
}

class JettyServlet3SyncV1ForkedTest extends JettyServlet3TestSync implements TestingGenericHttpNamingConventions.ServerV1 {
}

class JettyServlet3TestAsync extends JettyServlet3Test {

  @Override
  Class<Servlet> servlet() {
    TestServlet3.Async
  }

  @Override
  boolean testException() {
    // An exception in async dispatch will be logged and retried resulting in an infinite loop
    false
  }

  @Override
  boolean testTimeout() {
    true
  }
}

class JettyServlet3ASyncV1ForkedTest extends JettyServlet3TestAsync implements TestingGenericHttpNamingConventions.ServerV1 {
}

class JettyServlet3TestFakeAsync extends JettyServlet3Test {

  @Override
  Class<Servlet> servlet() {
    TestServlet3.FakeAsync
  }
}

class JettyServlet3TestForward extends JettyServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync // dispatch to sync servlet
  }

  @Override
  boolean isDispatch() {
    return true
  }

  @Override
  boolean testException() {
    // FIXME jetty attributes response spans incorrectly
    // see comment in cleanAndAssertTrace
    false
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    forwardSpan(trace, endpoint)
  }

  @Override
  protected void setupServlets(ServletContextHandler context) {
    super.setupServlets(context)
    setupDispatchServlets(context, RequestDispatcherServlet.Forward)
  }
}

class JettyServlet3TestInclude extends JettyServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync // dispatch to sync servlet
  }

  boolean isDispatch() {
    return true
  }

  @Override
  boolean testBlocking() {
    // setting response code from included dispatches is not supported by servlet,
    // and would require version-dependent hacks on Jetty
    false
  }

  @Override
  boolean testUserBlocking() {
    false
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    includeSpan(trace, endpoint)
  }

  boolean bubblesResponse() {
    return false
  }

  @Override
  boolean testException() {
    // FIXME jetty attributes response spans incorrectly
    // see comment in cleanAndAssertTrace
    false
  }

  @Override
  protected void setupServlets(ServletContextHandler context) {
    super.setupServlets(context)
    setupDispatchServlets(context, RequestDispatcherServlet.Include)
  }
}

class JettyServlet3TestDispatchImmediate extends JettyServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }

  boolean isDispatch() {
    return true
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    dispatchSpan(trace, endpoint)
  }

  @Override
  protected void setupServlets(ServletContextHandler context) {
    super.setupServlets(context)
    setupDispatchServlets(context, TestServlet3.DispatchImmediate)
    addServlet(context, "/dispatch/recursive", TestServlet3.DispatchRecursive)
  }
}

class JettyServlet3TestDispatchAsync extends JettyServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Async
  }

  @Override
  boolean testException() {
    // An exception in async dispatch will be logged and retried resulting in an infinite loop
    false
  }

  @Override
  boolean testTimeout() {
    return true
  }

  @Override
  boolean isDispatch() {
    return true
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    dispatchSpan(trace, endpoint)
  }

  @Override
  protected void setupServlets(ServletContextHandler context) {
    super.setupServlets(context)
    setupDispatchServlets(context, TestServlet3.DispatchAsync)
    addServlet(context, "/dispatch/recursive", TestServlet3.DispatchRecursive)
  }
}


@WebServlet(asyncSupported = true)
class DispatchTimeoutAsync extends HttpServlet {
  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) {
    def target = req.servletPath.replace("/dispatch", "")
    def context = req.startAsync()
    context.addListener(new AsyncListener() {
        @Override
        void onComplete(AsyncEvent event) throws IOException {}

        @Override
        void onTimeout(AsyncEvent event) throws IOException {
          event.asyncContext.dispatch(target)
        }

        @Override
        void onError(AsyncEvent event) throws IOException {}

        @Override
        void onStartAsync(AsyncEvent event) throws IOException {}
      })
    context.timeout = 1
  }
}

class JettyServlet3TestSyncDispatchOnAsyncTimeout extends JettyServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }

  @Override
  boolean isDispatch() {
    true
  }

  @Override
  boolean testParallelRequest() {
    false
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    dispatchSpan(trace, endpoint)
  }

  @Override
  protected void setupServlets(ServletContextHandler context) {
    super.setupServlets(context)
    setupDispatchServlets(context, DispatchTimeoutAsync)
    addServlet(context, "/dispatch/recursive", TestServlet3.DispatchRecursive)
  }
}

//@Flaky("Fails with timeout very often under high load")
@Retry(exceptions = SocketTimeoutException, count = 3, delay = 500, mode = Retry.Mode.SETUP_FEATURE_CLEANUP)
class JettyServlet3TestAsyncDispatchOnAsyncTimeout extends JettyServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Async
  }

  @Override
  boolean testTimeout() {
    true
  }

  boolean testException() {
    false
  }


  @Override
  boolean isDispatch() {
    true
  }

  @Override
  boolean testParallelRequest() {
    false
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    dispatchSpan(trace, endpoint)
  }

  @Override
  protected void setupServlets(ServletContextHandler context) {
    super.setupServlets(context)
    setupDispatchServlets(context, DispatchTimeoutAsync)
    addServlet(context, "/dispatch/recursive", TestServlet3.DispatchRecursive)
  }
}

@WebServlet(asyncSupported = true)
class ServeFromOnAsyncTimeout extends HttpServlet {
  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) {
    def context = req.startAsync()
    context.addListener(new AsyncListener() {
        Servlet delegateServlet = new TestServlet3.Sync()

        @Override
        void onComplete(AsyncEvent event) throws IOException {}

        @Override
        void onTimeout(AsyncEvent event) throws IOException {
          Context ddContext = (Context) event.getSuppliedRequest().getAttribute(DD_CONTEXT_ATTRIBUTE)
          AgentSpan span = spanFromContext(ddContext)
          activateSpan(span).withCloseable {
            try {
              delegateServlet.service(req, resp)
            } finally {
              event.asyncContext.complete()
            }
          }
        }

        @Override
        void onError(AsyncEvent event) throws IOException {}

        @Override
        void onStartAsync(AsyncEvent event) throws IOException {}
      })
    context.timeout = 1
  }
}

//@Flaky("Fails with timeout very often under high load")
@Retry(exceptions = SocketTimeoutException, count = 3, delay = 500, mode = Retry.Mode.SETUP_FEATURE_CLEANUP)
class JettyServlet3ServeFromAsyncTimeout extends JettyServlet3Test {
  @Override
  Class<Servlet> servlet() {
    ServeFromOnAsyncTimeout
  }

  @Override
  boolean testException() {
    false
  }

  @Override
  boolean testParallelRequest() {
    false
  }
}

class IastJettyServlet3ForkedTest extends JettyServlet3TestSync {

  @Override
  Class<Servlet> servlet() {
    return TestServlet3.GetSession
  }

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
    0 * appModule.checkSessionTrackingModes(_)
    0 * _
  }

  void 'test that iast module is called'() {
    given:
    final appModule = Mock(ApplicationModule)
    InstrumentationBridge.registerIastModule(appModule)
    def request = request(SUCCESS, "GET", null).build()

    when:
    client.newCall(request).execute()

    then:
    1 * appModule.onRealPath(_)
    1 * appModule.checkSessionTrackingModes(_)
    0 * _

    when:
    client.newCall(request).execute()

    then: //Only call once per application context
    0 * appModule.onRealPath(_)
    0 * appModule.checkSessionTrackingModes(_)
    0 * _
  }
}
