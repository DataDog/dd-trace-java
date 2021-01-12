import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.servlet3.AsyncDispatcherDecorator
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.servlet.ServletContextHandler

import javax.servlet.Servlet
import javax.servlet.http.HttpServletRequest

import static TestServlet3.SERVLET_TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR

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
  String component() {
    return "jetty-server"
  }

  @Override
  String getContext() {
    return "jetty-context"
  }

  @Override
  void addServlet(ServletContextHandler servletContext, String path, Class<Servlet> servlet) {
    servletContext.addServlet(servlet, path)
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

  void dispatchSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "servlet.dispatch"
      resourceName endpoint.path
      errored(endpoint.errored && endpoint != TIMEOUT)
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" AsyncDispatcherDecorator.DECORATE.component()
        if (endpoint != TIMEOUT && endpoint != TIMEOUT_ERROR) {
          "$Tags.HTTP_STATUS" endpoint.status
        } else {
          "timeout" SERVLET_TIMEOUT
        }
        if (context) {
          "servlet.context" "/$context"
        }
        "servlet.path" "/dispatch$endpoint.path"
        if (endpoint.errored) {
          "error.msg" { it == null || it == EXCEPTION.body }
          "error.type" { it == null || it == Exception.name }
          "error.stack" { it == null || it instanceof String }
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

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == ERROR || (endpoint == EXCEPTION && !IS_LATEST) || endpoint == NOT_FOUND
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

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == ERROR || (endpoint == EXCEPTION && !IS_LATEST) || endpoint == NOT_FOUND
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

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    return endpoint == REDIRECT || endpoint == ERROR || (endpoint == EXCEPTION && !IS_LATEST) || endpoint == NOT_FOUND
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
