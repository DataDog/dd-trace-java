import com.google.common.io.Files
import datadog.trace.api.CorrelationIdentifier
import org.apache.catalina.AccessLog
import org.apache.catalina.Context
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.valves.ErrorReportValve
import org.apache.catalina.valves.ValveBase
import org.apache.tomcat.JarScanFilter
import org.apache.tomcat.JarScanType
import spock.lang.Shared
import spock.lang.Unroll

import javax.servlet.Servlet
import javax.servlet.ServletException

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.AUTH_REQUIRED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR

@Unroll
abstract class TomcatServlet3Test extends AbstractServlet3Test<Tomcat, Context> {

  @Shared
  def accessLogValue = new TestAccessLogValve()

  @Override
  Tomcat startServer(int port) {
    def tomcatServer = new Tomcat()

    def baseDir = Files.createTempDir()
    baseDir.deleteOnExit()
    tomcatServer.setBaseDir(baseDir.getAbsolutePath())

    tomcatServer.setPort(port)
    tomcatServer.getConnector().enableLookups = true // get localhost instead of 127.0.0.1

    final File applicationDir = new File(baseDir, "/webapps/ROOT")
    if (!applicationDir.exists()) {
      applicationDir.mkdirs()
      applicationDir.deleteOnExit()
    }
    Context servletContext = tomcatServer.addWebapp("/$context", applicationDir.getAbsolutePath())
    // Speed up startup by disabling jar scanning:
    servletContext.getJarScanner().setJarScanFilter(new JarScanFilter() {
      @Override
      boolean check(JarScanType jarScanType, String jarName) {
        return false
      }
    })

//    setupAuthentication(tomcatServer, servletContext)
    setupServlets(servletContext)

    (tomcatServer.host as StandardHost).errorReportValveClass = ErrorHandlerValve.name
    (tomcatServer.host as StandardHost).getPipeline().addValve(accessLogValue)

    tomcatServer.start()

    return tomcatServer
  }

  def setup() {
    accessLogValue.loggedIds.clear()
  }

  @Override
  void stopServer(Tomcat server) {
    server.stop()
    server.destroy()
  }

  @Override
  String getContext() {
    return "tomcat-context"
  }

  @Override
  void addServlet(Context servletContext, String path, Class<Servlet> servlet) {
    String name = UUID.randomUUID()
    Tomcat.addServlet(servletContext, name, servlet.newInstance())
    servletContext.addServletMappingDecoded(path, name)
  }


  def "access log has ids for #count requests"() {
    given:
    def request = request(SUCCESS, method, body).build()

    when:
    List<okhttp3.Response> responses = (1..count).collect {
      return client.newCall(request).execute()
    }

    then:
    responses.each { response ->
      assert response.code() == SUCCESS.status
      assert response.body().string() == SUCCESS.body
    }

    and:
    cleanAndAssertTraces(count) {
      (1..count).eachWithIndex { val, i ->
        if (hasHandlerSpan()) {
          trace(3) {
            sortSpansByStart()
            serverSpan(it)
            handlerSpan(it, span(0))
            controllerSpan(it, span(1))
          }
        } else {
          trace(2) {
            serverSpan(it)
            controllerSpan(it, span(0))
          }
        }

        def (String traceId, String spanId) = accessLogValue.loggedIds[i]
        def idIndex = hasHandlerSpan() ? 1 : 0
        assert trace(i).get(idIndex).traceId.toString() == traceId
        assert trace(i).get(idIndex).spanId.toString() == spanId
      }
    }

    where:
    method = "GET"
    body = null
    count << [1, 4] // make multiple requests.
  }

  def "access log has ids for error request"() {
    setup:
    def request = request(ERROR, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == ERROR.status
    response.body().string() == ERROR.body

    and:
    cleanAndAssertTraces(1) {
      if (hasHandlerSpan()) {
        trace(3) {
          sortSpansByStart()
          serverSpan(it, null, null, method, ERROR)
          handlerSpan(it, span(0), ERROR)
          controllerSpan(it, span(1))
        }
      } else {
        trace(2) {
          serverSpan(it, null, null, method, ERROR)
          controllerSpan(it, span(0))
        }
      }

      def (String traceId, String spanId) = accessLogValue.loggedIds[0]
      def idIndex = hasHandlerSpan() ? 1 : 0
      assert trace(0).get(idIndex).traceId.toString() == traceId
      assert trace(0).get(idIndex).spanId.toString() == spanId
    }

    where:
    method = "GET"
    body = null
  }

  // FIXME: Add authentication tests back in...
//  private setupAuthentication(Tomcat server, Context servletContext) {
//    // Login Config
//    LoginConfig authConfig = new LoginConfig()
//    authConfig.setAuthMethod("BASIC")
//
//    // adding constraint with role "test"
//    SecurityConstraint constraint = new SecurityConstraint()
//    constraint.addAuthRole("role")
//
//    // add constraint to a collection with pattern /second
//    SecurityCollection collection = new SecurityCollection()
//    collection.addPattern("/auth/*")
//    constraint.addCollection(collection)
//
//    servletContext.setLoginConfig(authConfig)
//    // does the context need a auth role too?
//    servletContext.addSecurityRole("role")
//    servletContext.addConstraint(constraint)
//
//    // add tomcat users to realm
//    MemoryRealm realm = new MemoryRealm() {
//      protected void startInternal() {
//        credentialHandler = new MessageDigestCredentialHandler()
//        setState(LifecycleState.STARTING)
//      }
//    }
//    realm.addUser(user, pass, "role")
//    server.getEngine().setRealm(realm)
//
//    servletContext.setLoginConfig(authConfig)
//  }
}

class ErrorHandlerValve extends ErrorReportValve {
  @Override
  protected void report(Request request, Response response, Throwable t) {
    if (response.getStatus() < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
      return
    }
    try {
      response.writer.print(t ? t.cause.message : response.message)
    } catch (IOException e) {
      e.printStackTrace()
    }
  }
}

class TestAccessLogValve extends ValveBase implements AccessLog {
  List<Tuple2<String, String>> loggedIds = []

  TestAccessLogValve() {
    super(true)
  }

  void log(Request request, Response response, long time) {
    loggedIds.add(new Tuple2(request.getAttribute(CorrelationIdentifier.traceIdKey),
      request.getAttribute(CorrelationIdentifier.spanIdKey)))
  }

  @Override
  void setRequestAttributesEnabled(boolean requestAttributesEnabled) {
  }

  @Override
  boolean getRequestAttributesEnabled() {
    return false
  }

  @Override
  void invoke(Request request, Response response) throws IOException, ServletException {
    getNext().invoke(request, response)
  }
}

class TomcatServlet3TestSync extends TomcatServlet3Test {

  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }
}

class TomcatServlet3TestAsync extends TomcatServlet3Test {

  @Override
  Class<Servlet> servlet() {
    TestServlet3.Async
  }

  @Override
  boolean testTimeout() {
    true
  }
}

class TomcatServlet3TestFakeAsync extends TomcatServlet3Test {

  @Override
  Class<Servlet> servlet() {
    TestServlet3.FakeAsync
  }
}

// FIXME: not working right now...
//class TomcatServlet3TestForward extends TomcatServlet3Test {
//  @Override
//  Class<Servlet> servlet() {
//    TestServlet3.Sync // dispatch to sync servlet
//  }
//
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
//  protected void setupServlets(Context context) {
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
//class TomcatServlet3TestInclude extends TomcatServlet3Test {
//  @Override
//  Class<Servlet> servlet() {
//    TestServlet3.Sync // dispatch to sync servlet
//  }
///
//boolean isDispatch() {
//  return true
//}
//  @Override
//  boolean testNotFound() {
//    false
//  }
//
//  @Override
//  protected void setupServlets(Context context) {
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

class TomcatServlet3TestDispatchImmediate extends TomcatServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }

  boolean isDispatch() {
    return true
  }

  @Override
  boolean testNotFound() {
    false
  }

  @Override
  protected void setupServlets(Context context) {
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

class TomcatServlet3TestDispatchAsync extends TomcatServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Async
  }

  boolean isDispatch() {
    return true
  }

  @Override
  boolean testNotFound() {
    false
  }

  @Override
  boolean testTimeout() {
    true
  }

  @Override
  protected void setupServlets(Context context) {
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
