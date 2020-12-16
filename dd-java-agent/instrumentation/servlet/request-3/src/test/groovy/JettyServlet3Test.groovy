import datadog.trace.agent.test.asserts.TraceAssert
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.servlet.ServletContextHandler

import javax.servlet.Servlet
import javax.servlet.http.HttpServletRequest

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

abstract class JettyServlet3Test extends AbstractServlet3Test<Server, ServletContextHandler> {
  @Override
  boolean testNotFound() {
    false
  }

  @Override
  Server startServer(int port) {
    def jettyServer = new Server(port)
    jettyServer.connectors.each {
      it.setHost('localhost')
    }

    ServletContextHandler servletContext = new ServletContextHandler(null, "/$context")
    servletContext.errorHandler = new ErrorHandler() {
      protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
        Throwable th = (Throwable) request.getAttribute("javax.servlet.error.exception")
        writer.write(th ? th.message : message)
      }
    }
//    setupAuthentication(jettyServer, servletContext)
    setupServlets(servletContext)
    jettyServer.setHandler(servletContext)

    jettyServer.start()

    return jettyServer
  }

  @Override
  void stopServer(Server server) {
    server.stop()
    server.destroy()
  }

  @Override
  String getContext() {
    return "jetty-context"
  }

  @Override
  void addServlet(ServletContextHandler servletContext, String path, Class<Servlet> servlet) {
    servletContext.addServlet(servlet, path)
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

class JettyServlet3TestAsync extends JettyServlet3Test {

  @Override
  Class<Servlet> servlet() {
    TestServlet3.Async
  }

  @Override
  boolean testTimeout() {
    true
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    // No response spans for errors in async
    return endpoint == REDIRECT || endpoint == NOT_FOUND
  }
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
  boolean testTimeout() {
    return true
  }

  boolean isDispatch() {
    return true
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    // No response spans for errors in async
    return endpoint == REDIRECT || endpoint == NOT_FOUND
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
