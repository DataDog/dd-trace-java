import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.servlet.ServletContextHandler

import javax.servlet.Servlet
import javax.servlet.http.HttpServletRequest

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.AUTH_REQUIRED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR

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
}

class JettyServlet3TestFakeAsync extends JettyServlet3Test {

  @Override
  Class<Servlet> servlet() {
    TestServlet3.FakeAsync
  }
}

// FIXME: not working right now...
//class JettyServlet3TestForward extends JettyServlet3Test {
//  @Override
//  Class<Servlet> servlet() {
//    TestServlet3.Sync // dispatch to sync servlet
//  }
//
//boolean isDispatch() {
//  return true
//}
//  @Override
//  boolean testNotFound() {
//    false
//  }
//
//  @Override
//  protected void setupServlets(ServletContextHandler context) {
//    super.setupServlets(context)
//
//    addServlet(context, "/dispatch" + SUCCESS.path, RequestDispatcherServlet.Forward)
//    addServlet(context, "/dispatch" + QUERY_PARAM.path, RequestDispatcherServlet.Forward)
//    addServlet(context, "/dispatch" + REDIRECT.path, RequestDispatcherServlet.Forward)
//    addServlet(context, "/dispatch" + ERROR.path, RequestDispatcherServlet.Forward)
//    addServlet(context, "/dispatch" + EXCEPTION.path, RequestDispatcherServlet.Forward)
//    addServlet(context, "/dispatch" + AUTH_REQUIRED.path, RequestDispatcherServlet.Forward)
//  }
//}

// FIXME: not working right now...
//class JettyServlet3TestInclude extends JettyServlet3Test {
//  @Override
//  Class<Servlet> servlet() {
//    TestServlet3.Sync // dispatch to sync servlet
//  }
//
//  boolean isDispatch() {
//    return true
//  }
//
//  @Override
//  boolean testNotFound() {
//    false
//  }
//
//  @Override
//  protected void setupServlets(ServletContextHandler context) {
//    super.setupServlets(context)
//
//    addServlet(context, "/dispatch" + SUCCESS.path, RequestDispatcherServlet.Include)
//    addServlet(context, "/dispatch" + QUERY_PARAM.path, RequestDispatcherServlet.Include)
//    addServlet(context, "/dispatch" + REDIRECT.path, RequestDispatcherServlet.Include)
//    addServlet(context, "/dispatch" + ERROR.path, RequestDispatcherServlet.Include)
//    addServlet(context, "/dispatch" + EXCEPTION.path, RequestDispatcherServlet.Include)
//    addServlet(context, "/dispatch" + AUTH_REQUIRED.path, RequestDispatcherServlet.Include)
//  }
//}


class JettyServlet3TestDispatchImmediate extends JettyServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }

  boolean isDispatch() {
    return true
  }

  @Override
  protected void setupServlets(ServletContextHandler context) {
    super.setupServlets(context)

    addServlet(context, "/dispatch" + SUCCESS.path, TestServlet3.DispatchImmediate)
    addServlet(context, "/dispatch" + QUERY_PARAM.path, TestServlet3.DispatchImmediate)
    addServlet(context, "/dispatch" + ERROR.path, TestServlet3.DispatchImmediate)
    addServlet(context, "/dispatch" + EXCEPTION.path, TestServlet3.DispatchImmediate)
    addServlet(context, "/dispatch" + REDIRECT.path, TestServlet3.DispatchImmediate)
    addServlet(context, "/dispatch" + AUTH_REQUIRED.path, TestServlet3.DispatchImmediate)
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

  @Override
  protected void setupServlets(ServletContextHandler context) {
    super.setupServlets(context)

    addServlet(context, "/dispatch" + SUCCESS.path, TestServlet3.DispatchAsync)
    addServlet(context, "/dispatch" + QUERY_PARAM.path, TestServlet3.DispatchAsync)
    addServlet(context, "/dispatch" + ERROR.path, TestServlet3.DispatchAsync)
    addServlet(context, "/dispatch" + EXCEPTION.path, TestServlet3.DispatchAsync)
    addServlet(context, "/dispatch" + REDIRECT.path, TestServlet3.DispatchAsync)
    addServlet(context, "/dispatch" + AUTH_REQUIRED.path, TestServlet3.DispatchAsync)
    addServlet(context, "/dispatch" + TIMEOUT.path, TestServlet3.DispatchAsync)
    addServlet(context, "/dispatch" + TIMEOUT_ERROR.path, TestServlet3.DispatchAsync)
    addServlet(context, "/dispatch/recursive", TestServlet3.DispatchRecursive)
  }
}
