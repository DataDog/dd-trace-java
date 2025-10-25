import com.google.common.io.Files
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.CorrelationIdentifier
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.servlet3.AsyncDispatcherDecorator
import datadog.trace.instrumentation.servlet3.TestServlet3
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
import javax.servlet.Servlet
import javax.servlet.ServletException

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.instrumentation.servlet3.TestServlet3.SERVLET_TIMEOUT

abstract class TomcatServlet3Test extends AbstractServlet3Test<Tomcat, Context> {

  @Shared
  TestAccessLogValve accessLogValue

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  boolean testSessionId() {
    true
  }

  class TomcatServer implements HttpServer {
    def port = 0
    final Tomcat server
    def accessLogValue = new TestAccessLogValve()

    TomcatServer() {
      server = new Tomcat()

      def baseDir = Files.createTempDir()
      baseDir.deleteOnExit()
      server.setBaseDir(baseDir.getAbsolutePath())

      server.setPort(port)
      server.getConnector().enableLookups = true // get localhost instead of 127.0.0.1

      final File applicationDir = new File(baseDir, "/webapps/ROOT")
      if (!applicationDir.exists()) {
        applicationDir.mkdirs()
        applicationDir.deleteOnExit()
      }
      Context servletContext = server.addWebapp("/$context", applicationDir.getAbsolutePath())
      servletContext.allowCasualMultipartParsing = true
      // Speed up startup by disabling jar scanning:
      servletContext.getJarScanner().setJarScanFilter(new JarScanFilter() {
          @Override
          boolean check(JarScanType jarScanType, String jarName) {
            return false
          }
        })

      //    setupAuthentication(tomcatServer, servletContext)
      setupServlets(servletContext)

      (server.host as StandardHost).errorReportValveClass = ErrorHandlerValve.name
      (server.host as StandardHost).getPipeline().addValve(accessLogValue)
    }

    @Override
    void start() {
      server.start()
      port = server.service.findConnectors()[0].localPort
      assert port > 0
    }

    @Override
    void stop() {
      //      sleep 10_000
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
    return new TomcatServer()
  }

  def setup() {
    accessLogValue = (server as TomcatServer).accessLogValue
    accessLogValue.loggedIds.clear()
  }

  @Override
  String component() {
    return "tomcat-server"
  }

  //@Ignore("https://github.com/DataDog/dd-trace-java/pull/5213")
  @Override
  boolean testBadUrl() {
    // Tomcat seems to exit too early:
    //   java.lang.IllegalArgumentException: Invalid character found in the request target. The valid characters are defined in RFC 7230 and RFC 3986
    false
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.throwsException) {
      // Exception classes get wrapped in ServletException
      ["error.message": { endpoint == EXCEPTION ? "Servlet execution threw an exception" : it == endpoint.body },
        "error.type": { it == ServletException.name || it == InputMismatchException.name },
        "error.stack": String]
    } else {
      Collections.emptyMap()
    }
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

  boolean handlerTriggersValue() {
    return true
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
    assertTraces(count) {
      Set<Tuple2<String, String>> loggedSpanIds = accessLogValue.loggedIds.toSet()
      assert loggedSpanIds.size() == count
      (1..count).eachWithIndex { val, i ->
        trace(spanCount(SUCCESS)) {
          sortSpansByStart()
          serverSpan(it)
          if (hasHandlerSpan()) {
            handlerSpan(it)
          }
          controllerSpan(it)
          if (hasResponseSpan(SUCCESS)) {
            responseSpan(it, SUCCESS)
          }
        }

        def ids = new Tuple2(trace(i).get(0).localRootSpan.traceId.toString(), trace(i).get(0).localRootSpan.spanId.toString())
        assert ids in loggedSpanIds
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
    if (bubblesResponse()) {
      assert response.code() == ERROR.status
      assert response.body().string() == ERROR.body
    }

    and:
    assertTraces(1) {
      trace(spanCount(ERROR)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, ERROR)
        if (hasHandlerSpan()) {
          handlerSpan(it, ERROR)
        }
        controllerSpan(it)
        if (hasResponseSpan(ERROR)) {
          responseSpan(it, ERROR)
        }
      }

      def (String traceId, String spanId) = accessLogValue.loggedIds[0]
      assert trace(0).get(0).getLocalRootSpan().traceId.toString() == traceId
      assert trace(0).get(0).getLocalRootSpan().spanId.toString() == spanId
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
    if (!response.error) {
      return
    }
    try {
      if (t) {
        if (t instanceof ServletException) {
          t = t.rootCause
        }
        while (t.cause != null) {
          t = t.cause
        }
        if (t instanceof InputMismatchException) {
          response.status = CUSTOM_EXCEPTION.status
        }
        response.reporter.write(t.message)
      } else if (response.message) {
        response.reporter.write(response.message)
      }

    } catch (IOException e) {
      e.printStackTrace()
    }
  }
}

class TestAccessLogValve extends ValveBase implements AccessLog {
  List<Tuple2<String, String>> loggedIds = Collections.<Tuple2<String, String>>synchronizedList([])

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
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }
}

class TomcatServlet3SyncV1ForkedTest extends TomcatServlet3TestSync implements TestingGenericHttpNamingConventions.ServerV1 {

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

  @Override
  boolean testException() {
    // The exception will just cause an async timeout
    false
  }

}

class TomcatServlet3AsyncV1ForkedTest extends TomcatServlet3TestAsync implements TestingGenericHttpNamingConventions.ServerV1 {

}

class TomcatServlet3TestFakeAsync extends TomcatServlet3Test {

  @Override
  Class<Servlet> servlet() {
    TestServlet3.FakeAsync
  }
}

class TomcatServlet3TestForward extends TomcatServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync // dispatch to sync servlet
  }

  boolean isDispatch() {
    return true
  }

  boolean handlerTriggersValue() {
    return false
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    forwardSpan(trace, endpoint)
  }

  @Override
  protected void setupServlets(Context context) {
    super.setupServlets(context)
    setupDispatchServlets(context, RequestDispatcherServlet.Forward)
  }
}

class TomcatServlet3TestInclude extends TomcatServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync // dispatch to sync servlet
  }

  boolean isDispatch() {
    return true
  }

  boolean handlerTriggersValue() {
    return false
  }

  boolean bubblesResponse() {
    return false
  }

  @Override
  boolean testNotFound() {
    // NotFound throws an exception for Includes instead of a 404
    false
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    includeSpan(trace, endpoint)
  }

  @Override
  protected void setupServlets(Context context) {
    super.setupServlets(context)
    setupDispatchServlets(context, RequestDispatcherServlet.Include)
  }
}

class TomcatServlet3TestDispatchImmediate extends TomcatServlet3Test {
  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }

  boolean isDispatch() {
    return true
  }

  @Override
  boolean testException() {
    false
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    dispatchSpan(trace, endpoint)
  }

  @Override
  protected void setupServlets(Context context) {
    super.setupServlets(context)

    setupDispatchServlets(context, TestServlet3.DispatchImmediate)
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
  boolean testException() {
    false
  }

  @Override
  boolean testTimeout() {
    true
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    dispatchSpan(trace, endpoint)
  }

  @Override
  protected void setupServlets(Context context) {
    super.setupServlets(context)
    setupDispatchServlets(context, TestServlet3.DispatchAsync)
    addServlet(context, "/dispatch/recursive", TestServlet3.DispatchRecursive)
  }
}

class IastTomcatServlet3ForkedTest extends TomcatServlet3TestSync {

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
    1 *  appModule.onRealPath(_)
    1 *  appModule.checkSessionTrackingModes(_)
    0 * _

    when:
    client.newCall(request).execute()

    then: //Only call once per application context
    0 *  appModule.onRealPath(_)
    0 *  appModule.checkSessionTrackingModes(_)
    0 * _
  }

}
