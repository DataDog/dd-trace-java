package datadog.trace.agent.test.base

import ch.qos.logback.classic.Level
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Unroll

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ASYNC_TIMEOUT_ERROR
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static org.junit.Assume.assumeTrue

@Unroll
abstract class HttpServerTest<SERVER> extends WithHttpServer<SERVER> {

  public static final Logger SERVER_LOGGER = LoggerFactory.getLogger("http-server")
  static {
    ((ch.qos.logback.classic.Logger) SERVER_LOGGER).setLevel(Level.DEBUG)
  }

  @Shared
  String component = component()

  abstract String component()

  String expectedServiceName() {
    CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
  }

  abstract String expectedOperationName()

  boolean hasHandlerSpan() {
    false
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    false
  }

  boolean bubblesResponse() {
    // Some things like javax.servlet.RequestDispatcher.include() don't bubble headers or response codes to the
    // parent request.  This is specified in the spec
    true
  }

  int spanCount(ServerEndpoint endpoint) {
    return 2 + (hasHandlerSpan() ? 1 : 0) + (hasResponseSpan(endpoint) ? 1 : 0)
  }

  boolean redirectHasBody() {
    false
  }

  boolean testForwarded() {
    true
  }

  boolean testNotFound() {
    true
  }

  boolean testRedirect() {
    true
  }

  boolean testExceptionBody() {
    true
  }

  boolean testException() {
    true
  }

  boolean testTimeout() {
    false
  }

  /** Return the expected resource name */
  String testPathParam() {
    null
  }

  boolean tagServerSpanWithRoute() {
    false
  }



  enum ServerEndpoint {
    SUCCESS("success", 200, "success"),
    REDIRECT("redirect", 302, "/redirected"),
    FORWARDED("forwarded", 200, "1.2.3.4"),
    ERROR("error-status", 500, "controller error"), // "error" is a special path for some frameworks
    EXCEPTION("exception", 500, "controller exception"),
    CUSTOM_EXCEPTION("custom-exception", 510, "custom exception"), // exception thrown with custom error
    NOT_FOUND("not-found", 404, "not found"),
    NOT_HERE("not-here", 404, "not here"), // Explicitly returned 404 from a valid controller

    TIMEOUT("timeout", 500, null),
    TIMEOUT_ERROR("timeout_error", 500, null),

    // TODO: add tests for the following cases:
    QUERY_PARAM("query?some=query", 200, "some=query"),
    // OkHttp never sends the fragment in the request, so these cases don't work.
    //    FRAGMENT_PARAM("fragment#some-fragment", 200, "some-fragment"),
    //    QUERY_FRAGMENT_PARAM("query/fragment?some=query#some-fragment", 200, "some=query#some-fragment"),
    PATH_PARAM("path/123/param", 200, "123"),
    AUTH_REQUIRED("authRequired", 200, null),
    LOGIN("login", 302, null),
    UNKNOWN("", 451, null), // This needs to have a valid status code

    private final String path
    final String query
    final String fragment
    final int status
    final String body
    final Boolean errored
    final Boolean throwsException
    final boolean hasPathParam

    ServerEndpoint(String uri, int status, String body) {
      def uriObj = URI.create(uri)
      this.path = uriObj.path
      this.query = uriObj.query
      this.fragment = uriObj.fragment
      this.status = status
      this.body = body
      this.errored = status >= 500 || name().contains("ERROR")
      this.throwsException = name().contains("EXCEPTION")
      this.hasPathParam = body == "123"
    }

    String getPath() {
      return "/$path"
    }

    String rawPath() {
      return path
    }

    URI resolve(URI address) {
      // must be relative path to allow for servlet context
      return address.resolve(rawPath())
    }

    String resource(String method, URI address, String pathParam) {
      return path == "not-found" ? "404" : "$method ${hasPathParam ? pathParam : resolve(address).path}"
    }

    static {
      assert values().length == values().collect { it.path }.toSet().size(): "paths should be unique"
    }

    private static final Map<String, ServerEndpoint> PATH_MAP = values().collectEntries { [it.path, it]}

    static ServerEndpoint forPath(String path) {
      def endpoint = PATH_MAP.get(path)
      return endpoint != null ? endpoint : UNKNOWN
    }
  }

  Request.Builder request(ServerEndpoint uri, String method, RequestBody body) {
    def url = HttpUrl.get(uri.resolve(address)).newBuilder()
      .query(uri.query)
      .fragment(uri.fragment)
      .build()
    return new Request.Builder()
      .url(url)
      .method(method, body)
  }

  static <T> T controller(ServerEndpoint endpoint, Closure<T> closure) {
    assert activeSpan() != null: "Controller should have a parent span."
    assert activeScope().asyncPropagating: "Scope should be propagating async."
    if (endpoint == NOT_FOUND || endpoint == UNKNOWN) {
      return closure()
    }
    return runUnderTrace("controller", closure)
  }

  def "test success with #count requests"() {
    setup:
    def request = request(SUCCESS, method, body).build()
    List<Response> responses = (1..count).collect {
      return client.newCall(request).execute()
    }

    expect:
    responses.each { response ->
      assert response.code() == SUCCESS.status
      assert response.body().string() == SUCCESS.body
    }

    and:
    assertTraces(count) {
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
      }
    }

    where:
    method = "GET"
    body = null
    count << [1, 4, 50] // make multiple requests.
  }

  def "test forwarded request"() {
    setup:
    assumeTrue(testForwarded())
    def request = request(FORWARDED, method, body).header("x-forwarded-for", FORWARDED.body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == FORWARDED.status
    response.body().string() == FORWARDED.body

    and:
    assertTraces(1) {
      trace(spanCount(FORWARDED)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, FORWARDED)
        if (hasHandlerSpan()) {
          handlerSpan(it, FORWARDED)
        }
        controllerSpan(it)
        if (hasResponseSpan(FORWARDED)) {
          responseSpan(it, FORWARDED)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test success with parent"() {
    setup:
    def traceId = 123G
    def parentId = 456G
    def request = request(SUCCESS, method, body)
      .header("x-datadog-trace-id", traceId.toString())
      .header("x-datadog-parent-id", parentId.toString())
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == SUCCESS.status
    response.body().string() == SUCCESS.body

    and:
    assertTraces(1) {
      trace(spanCount(SUCCESS)) {
        sortSpansByStart()
        serverSpan(it, traceId, parentId, method)
        if (hasHandlerSpan()) {
          handlerSpan(it)
        }
        controllerSpan(it)
        if (hasResponseSpan(SUCCESS)) {
          responseSpan(it, SUCCESS)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test tag query string for #endpoint"() {
    setup:
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "true")
    def request = request(endpoint, method, body).build()

    when:
    Response response = client.newCall(request).execute()

    then:
    response.code() == endpoint.status
    response.body().string() == endpoint.body

    and:
    assertTraces(1) {
      trace(spanCount(endpoint)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, endpoint)
        if (hasHandlerSpan()) {
          handlerSpan(it, endpoint)
        }
        controllerSpan(it)
        if (hasResponseSpan(endpoint)) {
          responseSpan(it, endpoint)
        }
      }
    }

    where:
    method = "GET"
    body = null
    endpoint << [SUCCESS, QUERY_PARAM]
  }

  def "test path param"() {
    setup:
    assumeTrue(testPathParam() != null)
    def request = request(PATH_PARAM, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == PATH_PARAM.status
    response.body().string() == PATH_PARAM.body

    and:
    assertTraces(1) {
      trace(spanCount(PATH_PARAM)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, PATH_PARAM)
        if (hasHandlerSpan()) {
          handlerSpan(it, PATH_PARAM)
        }
        controllerSpan(it)
        if (hasResponseSpan(PATH_PARAM)) {
          responseSpan(it, PATH_PARAM)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test success with multiple header attached parent"() {
    setup:
    def traceId = 123G
    def parentId = 456G
    def request = request(SUCCESS, method, body)
      .header("x-datadog-trace-id", traceId.toString() + ", " + traceId.toString())
      .header("x-datadog-parent-id", parentId.toString() + ", " + parentId.toString())
      .header("x-datadog-sampling-priority", "1, 1")
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == SUCCESS.status
    response.body().string() == SUCCESS.body

    and:
    assertTraces(1) {
      trace(spanCount(SUCCESS)) {
        sortSpansByStart()
        serverSpan(it, traceId, parentId)
        if (hasHandlerSpan()) {
          handlerSpan(it)
        }
        controllerSpan(it)
        if (hasResponseSpan(SUCCESS)) {
          responseSpan(it, SUCCESS)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test redirect"() {
    setup:
    assumeTrue(testRedirect())
    def request = request(REDIRECT, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    if (bubblesResponse()) {
      assert response.code() == REDIRECT.status
      assert response.header("location") == REDIRECT.body ||
      response.header("location") == "${address.resolve(REDIRECT.body)}"
    }

    response.body().contentLength() < 1 || redirectHasBody()

    and:
    assertTraces(1) {
      trace(spanCount(REDIRECT)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, REDIRECT)
        if (hasHandlerSpan()) {
          handlerSpan(it, REDIRECT)
        }
        controllerSpan(it)
        if (hasResponseSpan(REDIRECT)) {
          responseSpan(it, REDIRECT)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test error"() {
    setup:
    def request = request(ERROR, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    if (bubblesResponse()) {
      response.code() == ERROR.status
      response.body().string() == ERROR.body
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
    }

    where:
    method = "GET"
    body = null
  }

  def "test exception"() {
    setup:
    assumeTrue(testException())
    def request = request(EXCEPTION, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == EXCEPTION.status
    if (testExceptionBody()) {
      assert response.body().string() == EXCEPTION.body
    }

    and:
    assertTraces(1) {
      trace(spanCount(EXCEPTION)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, EXCEPTION)
        if (hasHandlerSpan()) {
          handlerSpan(it, EXCEPTION)
        }
        controllerSpan(it, EXCEPTION)
        if (hasResponseSpan(EXCEPTION)) {
          responseSpan(it, EXCEPTION)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test notFound"() {
    setup:
    assumeTrue(testNotFound())
    def request = request(NOT_FOUND, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == NOT_FOUND.status

    and:
    assertTraces(1) {
      trace(spanCount(NOT_FOUND) - 1) {
        // no controller span
        sortSpansByStart()
        serverSpan(it, null, null, method, NOT_FOUND)
        if (hasHandlerSpan()) {
          handlerSpan(it, NOT_FOUND)
        }
        if (hasResponseSpan(NOT_FOUND)) {
          responseSpan(it, NOT_FOUND)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test timeout"() {
    setup:
    assumeTrue(testTimeout())
    injectSysConfig(SERVLET_ASYNC_TIMEOUT_ERROR, "false")
    def request = request(TIMEOUT, method, body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 500
    response.body().string() == ""
    response.body().contentLength() == 0

    and:
    assertTraces(1) {
      trace(spanCount(TIMEOUT)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, TIMEOUT)
        if (hasHandlerSpan()) {
          handlerSpan(it, TIMEOUT)
        }
        controllerSpan(it)
        if (hasResponseSpan(TIMEOUT)) {
          responseSpan(it, TIMEOUT)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test timeout as error"() {
    setup:
    assumeTrue(testTimeout())
    def request = request(TIMEOUT_ERROR, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    if (bubblesResponse()) {
      assert response.code() == 500
    }
    response.body().string() == ""
    response.body().contentLength() == 0

    and:
    assertTraces(1) {
      trace(spanCount(TIMEOUT_ERROR)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, TIMEOUT_ERROR)
        if (hasHandlerSpan()) {
          handlerSpan(it, TIMEOUT_ERROR)
        }
        controllerSpan(it)
        if (hasResponseSpan(TIMEOUT_ERROR)) {
          responseSpan(it, TIMEOUT_ERROR)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }

  //FIXME: add tests for POST with large/chunked data

  void controllerSpan(TraceAssert trace, ServerEndpoint endpoint = null) {
    def exception = endpoint == CUSTOM_EXCEPTION ? InputMismatchException : expectedExceptionType()
    def errorMessage = endpoint?.body
    trace.span {
      serviceName expectedServiceName()
      operationName "controller"
      resourceName "controller"
      errored errorMessage != null
      childOfPrevious()
      tags {
        if (errorMessage) {
          errorTags(exception, errorMessage)
        }
        defaultTags()
      }
      metrics {
        defaultMetrics()
      }
    }
  }

  Class<? extends Exception> expectedExceptionType() {
    return Exception
  }

  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    throw new UnsupportedOperationException("handlerSpan not implemented in " + getClass().name)
  }

  void responseSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    throw new UnsupportedOperationException("responseSpan not implemented in " + getClass().name)
  }

  // parent span must be cast otherwise it breaks debugging classloading (junit loads it early)
  void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    boolean tagServerSpanWithRoute = tagServerSpanWithRoute()
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
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_PORT" Integer
        "$Tags.PEER_HOST_IPV4" { endpoint == FORWARDED ? it == endpoint.body : (it == null || it == "127.0.0.1") }
        "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_STATUS" endpoint.status
        if (tagServerSpanWithRoute) {
          "$Tags.HTTP_ROUTE" String
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" endpoint.query
        }
        // OkHttp never sends the fragment in the request.
        //        if (endpoint.fragment) {
        //          "$DDTags.HTTP_FRAGMENT" endpoint.fragment
        //        }
        defaultTags(true)
      }
      metrics {
        defaultMetrics()
      }
    }
  }
}
