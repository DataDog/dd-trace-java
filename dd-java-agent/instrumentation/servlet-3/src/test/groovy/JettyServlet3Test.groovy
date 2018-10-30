import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.TestUtils
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.DDSpanTypes
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.eclipse.jetty.http.HttpHeaders
import org.eclipse.jetty.security.ConstraintMapping
import org.eclipse.jetty.security.ConstraintSecurityHandler
import org.eclipse.jetty.security.HashLoginService
import org.eclipse.jetty.security.LoginService
import org.eclipse.jetty.security.authentication.BasicAuthenticator
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.util.security.Constraint

class JettyServlet3Test extends AgentTestRunner {

  OkHttpClient client = OkHttpUtils.clientBuilder().addNetworkInterceptor(new Interceptor() {
    @Override
    Response intercept(Interceptor.Chain chain) throws IOException {
      def response = chain.proceed(chain.request())
      TEST_WRITER.waitForTraces(1)
      return response
    }
  })
    .build()

  int port
  private Server jettyServer
  private ServletContextHandler servletContext

  def setup() {
    port = TestUtils.randomOpenPort()
    jettyServer = new Server(port)
    servletContext = new ServletContextHandler()

    ConstraintSecurityHandler security = setupAuthentication(jettyServer)

    servletContext.setSecurityHandler(security)
    servletContext.addServlet(TestServlet3.Sync, "/sync")
    servletContext.addServlet(TestServlet3.Sync, "/auth/sync")
    servletContext.addServlet(TestServlet3.Async, "/async")
    servletContext.addServlet(TestServlet3.Async, "/auth/async")
    servletContext.addServlet(TestServlet3.AsyncWithBackgroundWork, "/asyncwithbackgroundwork")
    servletContext.addServlet(TestServlet3.Async, "/auth/asyncwithbackgroundwork")

    jettyServer.setHandler(servletContext)
    jettyServer.start()

    System.out.println(
      "Jetty server: http://localhost:" + port + "/")
  }

  def cleanup() {
    jettyServer.stop()
    jettyServer.destroy()
  }

  def "test #path servlet call (auth: #auth, distributed tracing: #distributedTracing)"() {
    setup:
    def requestBuilder = new Request.Builder()
      .url("http://localhost:$port/$path")
      .get()
    if (distributedTracing) {
      requestBuilder.header("x-datadog-trace-id", "123")
      requestBuilder.header("x-datadog-parent-id", "456")
    }
    if (auth) {
      requestBuilder.header(HttpHeaders.AUTHORIZATION, Credentials.basic("user", "password"))
    }
    def response = client.newCall(requestBuilder.build()).execute()

    expect:
    response.body().string().trim() == expectedResponse

    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          if (distributedTracing) {
            traceId "123"
            parentId "456"
          } else {
            parent()
          }
          serviceName "unnamed-java-app"
          operationName "servlet.request"
          resourceName "GET /$path"
          spanType DDSpanTypes.WEB_SERVLET
          errored false
          tags {
            "http.url" "http://localhost:$port/$path"
            "http.method" "GET"
            "span.kind" "server"
            "component" "java-web-servlet"
            "span.origin.type" "TestServlet3\$$origin"
            "span.type" DDSpanTypes.WEB_SERVLET
            "http.status_code" 200
            if (auth) {
              "user.principal" "user"
            }
            defaultTags(distributedTracing)
          }
        }
      }
    }

    where:
    path         | expectedResponse | auth  | origin  | distributedTracing
    "async"      | "Hello Async"    | false | "Async" | false
    "sync"       | "Hello Sync"     | false | "Sync"  | false
    "auth/async" | "Hello Async"    | true  | "Async" | false
    "auth/sync"  | "Hello Sync"     | true  | "Sync"  | false
    "async"      | "Hello Async"    | false | "Async" | true
    "sync"       | "Hello Sync"     | false | "Sync"  | true
    "auth/async" | "Hello Async"    | true  | "Async" | true
    "auth/sync"  | "Hello Sync"     | true  | "Sync"  | true
  }

  def "servlet instrumentation clears state after async request"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/async")
      .get()
      .build()
    def numTraces = 5
    for (int i = 0; i < numTraces; ++i) {
      client.newCall(request).execute()
    }

    expect:
    assertTraces(numTraces) {
      for (int i = 0; i < numTraces; ++i) {
        trace(i, 1) {
          span(0) {
            serviceName "unnamed-java-app"
            operationName "servlet.request"
            resourceName "GET /async"
          }
        }
      }
    }
  }

  def "test #path error servlet call"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/$path?error=true")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().string().trim() != expectedResponse

    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "servlet.request"
          resourceName "GET /$path"
          spanType DDSpanTypes.WEB_SERVLET
          errored true
          parent()
          tags {
            "http.url" "http://localhost:$port/$path"
            "http.method" "GET"
            "span.kind" "server"
            "component" "java-web-servlet"
            "span.type" DDSpanTypes.WEB_SERVLET
            "span.origin.type" "TestServlet3\$Sync"
            "http.status_code" 500
            errorTags(RuntimeException, "some $path error")
            defaultTags()
          }
        }
      }
    }

    where:
    path   | expectedResponse
    //"async" | "Hello Async" // FIXME: I can't seem get the async error handler to trigger
    "sync" | "Hello Sync"
  }

  def "test #path non-throwing-error servlet call"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/$path?non-throwing-error=true")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().string().trim() != expectedResponse

    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "servlet.request"
          resourceName "GET /$path"
          spanType DDSpanTypes.WEB_SERVLET
          errored true
          parent()
          tags {
            "http.url" "http://localhost:$port/$path"
            "http.method" "GET"
            "span.kind" "server"
            "component" "java-web-servlet"
            "span.type" DDSpanTypes.WEB_SERVLET
            "span.origin.type" "TestServlet3\$Sync"
            "http.status_code" 500
            "error" true
            defaultTags()
          }
        }
      }
    }

    where:
    path   | expectedResponse
    "sync" | "Hello Sync"
  }

  def "test async context propagation under servlet"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/asyncwithbackgroundwork")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().string().trim() == "AsyncWithBackground"

    assertTraces(2) {
      trace(0, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "servlet.request"
          resourceName "GET /asyncwithbackgroundwork"
          spanType DDSpanTypes.WEB_SERVLET
          errored false
          parent()
          tags {
            "http.url" "http://localhost:$port/asyncwithbackgroundwork"
            "http.method" "GET"
            "span.kind" "server"
            "component" "java-web-servlet"
            "span.type" DDSpanTypes.WEB_SERVLET
            "span.origin.type" "TestServlet3\$AsyncWithBackgroundWork"
            "http.status_code" 200
            defaultTags()
          }
        }
      }
      trace(1, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "not-http"
          parent()
          tags {
            defaultTags()
          }
        }
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
  private ConstraintSecurityHandler setupAuthentication(Server jettyServer) {
    ConstraintSecurityHandler security = new ConstraintSecurityHandler()

    Constraint constraint = new Constraint()
    constraint.setName("auth")
    constraint.setAuthenticate(true)
    constraint.setRoles("role")

    ConstraintMapping mapping = new ConstraintMapping()
    mapping.setPathSpec("/auth/*")
    mapping.setConstraint(constraint)

    security.setConstraintMappings(mapping)
    security.setAuthenticator(new BasicAuthenticator())

    LoginService loginService = new HashLoginService("TestRealm",
      "src/test/resources/realm.properties")
    security.setLoginService(loginService)
    jettyServer.addBean(loginService)

    security
  }
}
