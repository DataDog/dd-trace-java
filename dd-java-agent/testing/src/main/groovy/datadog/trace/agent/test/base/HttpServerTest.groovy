package datadog.trace.agent.test.base

import ch.qos.logback.classic.Level
import datadog.appsec.api.blocking.Blocking
import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingDetails
import datadog.appsec.api.blocking.BlockingException
import datadog.appsec.api.blocking.BlockingService
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.ProductActivation
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.http.StoredBodySupplier
import datadog.trace.api.normalize.SimpleHttpPathNormalizer
import datadog.trace.bootstrap.blocking.BlockingActionHelper
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.bootstrap.instrumentation.api.URIUtils
import datadog.trace.core.DDSpan
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.test.util.Flaky
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Unroll

import javax.annotation.Nonnull
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED_IS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_QUERY_STRING
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ASYNC_TIMEOUT_ERROR
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.RESPONSE_HEADER_TAGS
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType.JSON
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.SERVER_PATHWAY_EDGE_TAGS
import static java.nio.charset.StandardCharsets.UTF_8
import static org.junit.Assume.assumeTrue

@Unroll
abstract class HttpServerTest<SERVER> extends WithHttpServer<SERVER> {

  public static final Logger SERVER_LOGGER = LoggerFactory.getLogger("http-server")
  protected static final DSM_EDGE_TAGS = SERVER_PATHWAY_EDGE_TAGS.collect { key, value ->
    return key + ":" + value
  }
  static {
    ((ch.qos.logback.classic.Logger) SERVER_LOGGER).setLevel(Level.DEBUG)
  }

  @Override
  boolean isDataStreamsEnabled() {
    true
  }

  @CompileStatic
  def setupSpec() {
    // Register the Instrumentation Gateway callbacks
    def ss = get().getSubscriptionService(RequestContextSlot.APPSEC)
    def callbacks = new IGCallbacks()
    Events<IGCallbacks.Context> events = Events.get()
    ss.registerCallback(events.requestStarted(), callbacks.requestStartedCb)
    ss.registerCallback(events.requestEnded(), callbacks.requestEndedCb)
    ss.registerCallback(events.requestHeader(), callbacks.requestHeaderCb)
    ss.registerCallback(events.requestHeaderDone(), callbacks.requestHeaderDoneCb)
    ss.registerCallback(events.requestMethodUriRaw(), callbacks.requestUriRawCb)
    ss.registerCallback(events.requestClientSocketAddress(), callbacks.requestClientSocketAddressCb)
    ss.registerCallback(events.requestBodyStart(), callbacks.requestBodyStartCb)
    ss.registerCallback(events.requestBodyDone(), callbacks.requestBodyEndCb)
    ss.registerCallback(events.requestBodyProcessed(), callbacks.requestBodyObjectCb)
    ss.registerCallback(events.responseStarted(), callbacks.responseStartedCb)
    ss.registerCallback(events.responseHeader(), callbacks.responseHeaderCb)
    ss.registerCallback(events.responseHeaderDone(), callbacks.responseHeaderDoneCb)
    ss.registerCallback(events.requestPathParams(), callbacks.requestParamsCb)

    if (Config.get().getIastActivation() == ProductActivation.FULLY_ENABLED) {
      def iastSubService = get().getSubscriptionService(RequestContextSlot.IAST)
      def iastCallbacks = new IastIGCallbacks()
      Events<IastIGCallbacks.Context> iastEvents = Events.get()
      iastSubService.registerCallback(iastEvents.requestStarted(), iastCallbacks.requestStartedCb)
      iastSubService.registerCallback(iastEvents.requestEnded(), iastCallbacks.requestEndedCb)
    }
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // we inject this config because it's statically assigned and we cannot inject this at test level without forking
    // not starting with "/" made full url (http://..) matching but not the path portion (because starting with /)
    // this settings should not affect test results
    injectSysConfig(TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING, "**/success:*")

    injectSysConfig(HEADER_TAGS, 'x-datadog-test-both-header:both_header_tag')
    injectSysConfig(REQUEST_HEADER_TAGS, 'x-datadog-test-request-header:request_header_tag')
    // We don't inject a matching response header tag here since it would be always on and show up in all the tests
  }

  // used in blocking tests to check if the handler was skipped
  volatile boolean handlerRan

  void setup() {
    handlerRan = false
  }

  String component = component()

  abstract String component()

  String expectedServiceName() {
    CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
  }

  // here to go around a limitation in openliberty, where the service name
  // is set only at the end of the span and doesn't propagate down
  String expectedControllerServiceName() {
    expectedServiceName()
  }

  abstract String expectedOperationName()

  String expectedResourceName(ServerEndpoint endpoint, String method, URI address) {
    if (endpoint.status == 404 && (changesAll404s() || endpoint.path == "/not-found")) {
      return "404"
    } else if (endpoint.hasPathParam) {
      return "$method ${testPathParam()}"
    }
    def encoded = !hasDecodedResource()
    def path = encoded ? endpoint.resolve(address).rawPath : endpoint.resolve(address).path
    return "$method ${new SimpleHttpPathNormalizer().normalize(path, encoded)}"
  }

  String expectedUrl(ServerEndpoint endpoint, URI address) {
    URI url = endpoint.resolve(address)
    def path = Config.get().isHttpServerRawResource() && supportsRaw() ? url.rawPath : url.path
    def uri = URIUtils.buildURL(url.scheme, url.host, url.port, path)
    def qt = expectedQueryTag(endpoint)
    if (qt && !qt.isEmpty()) {
      uri = "$uri?$qt"
    }
    return uri
  }

  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    null
  }

  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    Collections.emptyMap()
  }

  // Only used if hasExtraErrorInformation is true
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.errored) {
      ["error.message"  : { it == null || it == EXCEPTION.body },
        "error.type" : { it == null || it == Exception.name },
        "error.stack": { it == null || it instanceof String }]
    } else {
      Collections.emptyMap()
    }
  }

  boolean expectedErrored(ServerEndpoint endpoint) {
    endpoint.errored
  }

  Serializable expectedStatus(ServerEndpoint endpoint) {
    endpoint.status
  }

  String expectedQueryTag(ServerEndpoint endpoint) {
    def encoded = Config.get().isHttpServerRawQueryString() && supportsRaw()
    def query = encoded ? endpoint.rawQuery : endpoint.query
    null != query && encoded && hasPlusEncodedSpaces() ? query.replaceAll('%20', "+") : query
  }

  Map<String, ?> expectedIGPathParams() {
    null
  }

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

  boolean hasDecodedResource() {
    !Config.get().isHttpServerRawResource() || !supportsRaw()
  }

  boolean hasPeerInformation() {
    true
  }

  boolean hasPeerPort() {
    true
  }

  boolean hasForwardedIP() {
    true
  }

  boolean hasExtraErrorInformation() {
    false
  }

  boolean changesAll404s() {
    false
  }

  boolean supportsRaw() {
    true
  }

  /** Does the raw query string contain plus encoded spaces? */
  boolean hasPlusEncodedSpaces() {
    false
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

  boolean testRequestBody() {
    false
  }

  boolean testRequestBodyISVariant() {
    false
  }

  boolean isRequestBodyNoStreaming() {
    // if true, plain text request body tests expect the requestBodyProcessed
    // callback to tbe called, not requestBodyStart/requestBodyDone
    false
  }

  boolean testBodyUrlencoded() {
    false
  }

  boolean testBodyMultipart() {
    false
  }

  boolean testBodyJson() {
    false
  }

  boolean testBlocking() {
    false
  }

  boolean testUserBlocking() {
    testBlocking()
  }

  boolean testBlockingOnResponse() {
    false
  }

  /** Tomcat 5.5 can't seem to handle the encoded URIs */
  boolean testEncodedPath() {
    true
  }

  /** Return the expected path parameter */
  String testPathParam() {
    null
  }

  boolean testMultipleHeader() {
    true
  }

  boolean testBadUrl() {
    true
  }

  boolean testResponseHeadersMapping() {
    true // bug in liberty-20
  }

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return expectedOperationName()
  }

  enum ServerEndpoint {
    SUCCESS("success", 200, "success"),
    CREATED("created", 201, "created"),
    CREATED_IS("created_input_stream", 201, "created"),
    BODY_URLENCODED("body-urlencoded?ignore=pair", 200, '[a:[x]]'),
    BODY_MULTIPART("body-multipart?ignore=pair", 200, '[a:[x]]'),
    BODY_JSON("body-json", 200, '{"a":"x"}'),
    REDIRECT("redirect", 302, "/redirected"),
    FORWARDED("forwarded", 200, "1.2.3.4"),
    ERROR("error-status", 500, "controller error"), // "error" is a special path for some frameworks
    EXCEPTION("exception", 500, "controller exception"),
    CUSTOM_EXCEPTION("custom-exception", 510, "custom exception"), // exception thrown with custom error
    NOT_FOUND("not-found", 404, "not found"),
    NOT_HERE("not-here", 404, "not here"), // Explicitly returned 404 from a valid controller

    TIMEOUT("timeout", 500, null),
    TIMEOUT_ERROR("timeout_error", 500, null),

    USER_BLOCK("user-block", 403, null),

    QUERY_PARAM("query?some=query", 200, "some=query"),
    QUERY_ENCODED_BOTH("encoded%20path%20query?some=is%20both", 200, "some=is both"),
    QUERY_ENCODED_QUERY("encoded_query?some=is%20query", 200, "some=is query"),
    // TODO: add tests for the following cases:
    // OkHttp never sends the fragment in the request, so these cases don't work.
    //    FRAGMENT_PARAM("fragment#some-fragment", 200, "some-fragment"),
    //    QUERY_FRAGMENT_PARAM("query/fragment?some=query#some-fragment", 200, "some=query#some-fragment"),
    PATH_PARAM("path/123/param", 200, "123"),
    MATRIX_PARAM("matrix/a=x,y;a=z", 200, '[a:[x, y, z]]'),
    AUTH_REQUIRED("authRequired", 200, null),
    LOGIN("login", 302, null),
    UNKNOWN("", 451, null), // This needs to have a valid status code

    private final String path
    private final String rawPath
    final String query
    final String rawQuery
    final String fragment
    final int status
    final String body
    final Boolean errored
    final Boolean throwsException
    final boolean hasPathParam

    ServerEndpoint(String uri, int status, String body) {
      def uriObj = URI.create(uri)
      this.path = uriObj.path
      this.rawPath = uriObj.rawPath
      this.query = uriObj.query
      this.rawQuery = uriObj.rawQuery
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

    String relativePath() {
      return path
    }

    String getRawPath() {
      return "/$rawPath"
    }

    String relativeRawPath() {
      return rawPath
    }

    URI resolve(URI address) {
      // must be relative path to allow for servlet context
      return address.resolve(relativeRawPath())
    }

    String bodyForQuery(String queryString) {
      if (queryString.equals(query) || queryString.equals(rawQuery)) {
        return body
      }
      return "non matching query string '$queryString'"
    }

    static {
      assert values().length == values().collect { it.path }.toSet().size(): "paths should be unique"
    }

    private static final Map<String, ServerEndpoint> PATH_MAP = {
      Map<String, ServerEndpoint> map = values().collectEntries { [it.path, it]}
      map.putAll(values().collectEntries { [it.rawPath, it]})
      map
    }.call()

    // Will match both decoded and encoded path
    static ServerEndpoint forPath(String path) {
      def endpoint = PATH_MAP.get(path)
      return endpoint != null ? endpoint : UNKNOWN
    }
  }

  Request.Builder request(ServerEndpoint uri, String method, RequestBody body) {
    def url = HttpUrl.get(uri.resolve(address)).newBuilder()
      .encodedQuery(uri.rawQuery)
      .fragment(uri.fragment)
      .build()
    return new Request.Builder()
      .url(url)
      .method(method, body)
  }

  static <T> T controller(ServerEndpoint endpoint, Closure<T> closure) {
    assert activeSpan() != null: "Controller should have a parent span."
    assert activeSpan() != noopSpan(): "Parent span shouldn't be noopSpan"
    assert activeScope().asyncPropagating: "Scope should be propagating async."
    if (endpoint == NOT_FOUND || endpoint == UNKNOWN) {
      return closure()
    }
    return runUnderTrace("controller", closure)
  }

  @Flaky(value = "https://github.com/DataDog/dd-trace-java/issues/4690", suites = ["MuleHttpServerForkedTest"])
  def "test success with #count requests"() {
    setup:
    def request = request(SUCCESS, method, body).build()
    List<Response> responses = (1..count).collect {
      return client.newCall(request).execute()
    }
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
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
    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method = "GET"
    body = null
    count << [1, 4, 50] // make multiple requests.
  }

  @Flaky(value = "https://github.com/DataDog/dd-trace-java/issues/4690", suites = ["MuleHttpServerForkedTest"])
  def "test forwarded request"() {
    setup:
    assumeTrue(testForwarded())
    def ip = FORWARDED.body
    def request = request(FORWARDED, method, body).header("x-forwarded-for", ip).build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.code() == FORWARDED.status
    response.body().string() == FORWARDED.body

    and:
    assertTraces(1) {
      trace(spanCount(FORWARDED)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, FORWARDED, null, ip)
        if (hasHandlerSpan()) {
          handlerSpan(it, FORWARDED)
        }
        controllerSpan(it)
        if (hasResponseSpan(FORWARDED)) {
          responseSpan(it, FORWARDED)
        }
      }
    }
    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method = "GET"
    body = null
  }

  @Flaky(value = "https://github.com/DataDog/dd-trace-java/issues/4690", suites = ["MuleHttpServerForkedTest"])
  def "test success with parent"() {
    setup:
    def traceId = 123G
    def parentId = 456G
    def request = request(SUCCESS, method, body)
      .header("x-datadog-trace-id", traceId.toString())
      .header("x-datadog-parent-id", parentId.toString())
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

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
    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method = "GET"
    body = null
  }

  @Flaky(value = "https://github.com/DataDog/dd-trace-java/issues/4690", suites = ["MuleHttpServerForkedTest"])
  def "test success with request header #header tag mapping"() {
    setup:
    def request = request(SUCCESS, method, body)
      .header(header, value)
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.code() == SUCCESS.status
    response.body().string() == SUCCESS.body

    and:
    assertTraces(1) {
      trace(spanCount(SUCCESS)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, SUCCESS, tags)
        if (hasHandlerSpan()) {
          handlerSpan(it)
        }
        controllerSpan(it)
        if (hasResponseSpan(SUCCESS)) {
          responseSpan(it, SUCCESS)
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method | body | header                           | value | tags
    'GET'  | null | 'x-datadog-test-both-header'     | 'foo' | [ 'both_header_tag': 'foo' ]
    'GET'  | null | 'x-datadog-test-request-header'  | 'bar' | [ 'request_header_tag': 'bar' ]
  }

  @Flaky(value = "https://github.com/DataDog/dd-trace-java/issues/4690", suites = ["MuleHttpServerForkedTest"])
  def "test QUERY_ENCODED_BOTH with response header x-ig-response-header tag mapping"() {
    setup:
    assumeTrue(testResponseHeadersMapping())
    def endpoint = QUERY_ENCODED_BOTH
    def method = 'GET'
    def body = null
    def header = IG_RESPONSE_HEADER
    def mapping = 'mapped_response_header_tag'
    def tags = ['mapped_response_header_tag': "$IG_RESPONSE_HEADER_VALUE" ]

    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "true")
    injectSysConfig(RESPONSE_HEADER_TAGS, "$header:$mapping")
    def request = request(endpoint, method, body)
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == endpoint.status
    response.body().string() == endpoint.body
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    and:
    assertTraces(1) {
      trace(spanCount(endpoint)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, endpoint, tags)
        if (hasHandlerSpan()) {
          handlerSpan(it, endpoint)
        }
        controllerSpan(it)
        if (hasResponseSpan(endpoint)) {
          responseSpan(it, endpoint)
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  @Flaky(value = "https://github.com/DataDog/dd-trace-java/issues/4690", suites = ["MuleHttpServerForkedTest"])
  def "test tag query string for #endpoint rawQuery=#rawQuery"() {
    setup:
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "true")
    injectSysConfig(HTTP_SERVER_RAW_QUERY_STRING, "$rawQuery")
    def request = request(endpoint, method, body).build()

    when:
    Response response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    rawQuery | endpoint
    true     | SUCCESS
    true     | QUERY_PARAM
    true     | QUERY_ENCODED_QUERY
    false    | SUCCESS
    false    | QUERY_PARAM
    false    | QUERY_ENCODED_QUERY

    method = "GET"
    body = null
  }

  def "test encoded path for #endpoint rawQuery=#rawQuery rawResource=#rawResource"() {
    setup:
    assumeTrue(testEncodedPath())
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "true")
    injectSysConfig(HTTP_SERVER_RAW_QUERY_STRING, "$rawQuery")
    injectSysConfig(HTTP_SERVER_RAW_RESOURCE, "$rawResource")
    def request = request(endpoint, method, body).build()

    when:
    Response response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    rawQuery | rawResource | endpoint
    true     | true        | QUERY_ENCODED_BOTH
    true     | false       | QUERY_ENCODED_BOTH
    false    | true        | QUERY_ENCODED_BOTH
    false    | false       | QUERY_ENCODED_BOTH

    method = "GET"
    body = null
  }

  def "test path param"() {
    setup:
    assumeTrue(testPathParam() != null)
    def request = request(PATH_PARAM, 'GET', null).build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.code() == PATH_PARAM.status
    response.body().string() == PATH_PARAM.body

    and:
    assertTraces(1) {
      trace(spanCount(PATH_PARAM)) {
        sortSpansByStart()
        serverSpan(it, null, null, 'GET', PATH_PARAM)
        if (hasHandlerSpan()) {
          handlerSpan(it, PATH_PARAM)
        }
        controllerSpan(it)
        if (hasResponseSpan(PATH_PARAM)) {
          responseSpan(it, PATH_PARAM)
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def "test path param publishes to IG"() {
    setup:
    assumeTrue(testPathParam() != null && expectedIGPathParams() != null)
    def request = request(PATH_PARAM, 'GET', null)
      .header(IG_EXTRA_SPAN_NAME_HEADER, 'appsec-span')
      .build()

    when:
    def response = client.newCall(request).execute()
    response.body().string() == PATH_PARAM.body
    TEST_WRITER.waitForTraces(2)
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    DDSpan span = TEST_WRITER.flatten().find {it.operationName =='appsec-span' }
    span.getTag(IG_PATH_PARAMS_TAG) == expectedIGPathParams()

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def "test success with multiple header attached parent"() {
    setup:
    assumeTrue(testMultipleHeader())
    def traceId = 123G
    def parentId = 456G
    def request = request(SUCCESS, method, body)
      .header("x-datadog-trace-id", traceId.toString() + ", " + traceId.toString())
      .header("x-datadog-parent-id", parentId.toString() + ", " + parentId.toString())
      .header("x-datadog-sampling-priority", "1, 1")
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method = "GET"
    body = null
  }

  def "test redirect"() {
    setup:
    assumeTrue(testRedirect())
    def request = request(REDIRECT, 'GET', null).build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

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
        serverSpan(it, null, null, 'GET', REDIRECT)
        if (hasHandlerSpan()) {
          handlerSpan(it, REDIRECT)
        }
        controllerSpan(it)
        if (hasResponseSpan(REDIRECT)) {
          responseSpan(it, REDIRECT)
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def "test error"() {
    setup:
    String method = 'GET'
    def request = request(ERROR, method, null).build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    if (bubblesResponse()) {
      assert response.body().string().contains(ERROR.body)
      assert response.code() == ERROR.status
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
        trailingSpans(it, ERROR)
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def "test exception"() {
    setup:
    def method = "GET"
    def body = null
    assumeTrue(testException())
    def request = request(EXCEPTION, method, body).build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

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
        trailingSpans(it, EXCEPTION)
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def "test notFound"() {
    setup:
    assumeTrue(testNotFound())

    String method = "GET"
    RequestBody body = null

    def request = request(NOT_FOUND, method, body).build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

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
        trailingSpans(it, NOT_FOUND)
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def "test timeout"() {
    setup:
    assumeTrue(testTimeout())
    injectSysConfig(SERVLET_ASYNC_TIMEOUT_ERROR, "false")
    def request = request(TIMEOUT, 'GET', null).build()

    when:
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    response.code() == 500
    response.body().string() == ""
    response.body().contentLength() == 0

    and:
    assertTraces(1) {
      trace(spanCount(TIMEOUT)) {
        sortSpansByStart()
        serverSpan(it, null, null, 'GET', TIMEOUT)
        if (hasHandlerSpan()) {
          handlerSpan(it, TIMEOUT)
        }
        controllerSpan(it)
        if (hasResponseSpan(TIMEOUT)) {
          responseSpan(it, TIMEOUT)
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def "test timeout as error"() {
    setup:
    assumeTrue(testTimeout())
    def request = request(TIMEOUT_ERROR, 'GET', null).build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

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
        serverSpan(it, null, null, 'GET', TIMEOUT_ERROR)
        if (hasHandlerSpan()) {
          handlerSpan(it, TIMEOUT_ERROR)
        }
        controllerSpan(it)
        if (hasResponseSpan(TIMEOUT_ERROR)) {
          responseSpan(it, TIMEOUT_ERROR)
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  //FIXME: add tests for POST with large/chunked data

  def "test instrumentation gateway callbacks for #endpoint with #header = #value"() {
    setup:
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, "true")
    def request = request(endpoint, "GET", null).header(header, value).build()
    def traces = extraSpan ? 2 : 1
    def extraTags = [(IG_RESPONSE_STATUS): String.valueOf(endpoint.status)] as Map<String, Serializable>
    if (hasPeerInformation()) {
      extraTags.put(IG_PEER_ADDRESS, { it == "127.0.0.1" || it == "0.0.0.0" })
      extraTags.put(IG_PEER_PORT, { Integer.parseInt(it as String) instanceof Integer })
    }
    extraTags.put(IG_RESPONSE_HEADER_TAG, IG_RESPONSE_HEADER_VALUE)

    when:
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    response.code() == endpoint.status
    response.body().string() == endpoint.body

    and:
    assertTraces(traces) {
      trace(spanCount(endpoint)) {
        sortSpansByStart()
        serverSpan(it, null, null, "GET", endpoint)
        if (hasHandlerSpan()) {
          handlerSpan(it, endpoint)
        }
        controllerSpan(it)
        if (hasResponseSpan(endpoint)) {
          responseSpan(it, endpoint)
        }
      }
      if (extraSpan) {
        trace(1) {
          basicSpan(it, "$header-$value", null, null, extraTags)
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    endpoint            | header         | value       | extraSpan
    QUERY_ENCODED_BOTH  | IG_TEST_HEADER | "something" | true
    QUERY_ENCODED_BOTH  | "x-ignored"    | "something" | false
    SUCCESS             | IG_TEST_HEADER | "whatever"  | false
    QUERY_ENCODED_QUERY | IG_TEST_HEADER | "whatever"  | false
    QUERY_PARAM         | IG_TEST_HEADER | "whatever"  | false
  }

  def 'test instrumentation gateway request body interception'() {
    setup:
    assumeTrue(testRequestBody())
    def request = request(
      CREATED, 'POST',
      RequestBody.create(MediaType.get('text/plain'), 'my body'))
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.body().charStream().text == 'created: my body'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    if (requestBodyNoStreaming) {
      assert TEST_WRITER.get(0).any {
        it.getTag('request.body.converted') == 'my body'
      }
    } else {
      assert TEST_WRITER.get(0).any {
        it.getTag('request.body') == 'my body'
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def 'test instrumentation gateway request body interception — InputStream variant'() {
    setup:
    assumeTrue(testRequestBodyISVariant())
    def request = request(
      CREATED_IS, 'POST',
      RequestBody.create(MediaType.get('text/plain'), 'my body'))
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.body().charStream().text == 'created: my body'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body') == 'my body'
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def 'test instrumentation gateway urlencoded request body'() {
    setup:
    assumeTrue(testBodyUrlencoded())
    def request = request(
      BODY_URLENCODED, 'POST',
      RequestBody.create(MediaType.get('application/x-www-form-urlencoded'), 'a=x'))
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.body().charStream().text == '[a:[x]]'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body.converted') == '[a:[x]]'
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }


  def 'test instrumentation gateway multipart request body'() {
    setup:
    assumeTrue(testBodyMultipart())
    def body = new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart('a', 'x')
      .build()
    def request = request(BODY_MULTIPART, 'POST', body).build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.body().charStream().text == '[a:[x]]'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body.converted') == '[a:[x]]'
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def 'test instrumentation gateway json request body'() {
    setup:
    assumeTrue(testBodyJson())
    def request = request(
      BODY_JSON, 'POST',
      RequestBody.create(MediaType.get('application/json'), '{"a": "x"}'))
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.body().charStream().text == BODY_JSON.body

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body.converted') == '[a:[x]]'
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  @Flaky(value = "https://github.com/DataDog/dd-trace-java/issues/4681", suites = ["GrizzlyAsyncTest", "GrizzlyTest"])
  def 'test blocking of request with auto and accept=#acceptHeader'(boolean expectedJson, String acceptHeader) {
    setup:
    assumeTrue(testBlocking())

    def request = request(SUCCESS, 'GET', null)
      .addHeader(IG_BLOCK_HEADER, 'auto').with {
        if (acceptHeader) {
          it.addHeader('Accept', 'text/html;q=0.9, application/json;q=0.8')
        }
        it.build()
      }
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.code() == 418
    if (expectedJson) {
      response.header('Content-type') =~ /(?i)\Aapplication\/json(?:;\s?charset=utf-8)?\z/
      response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    } else {
      response.header('Content-type') =~ /(?i)\Atext\/html;\s?charset=utf-8\z/
      response.body().charStream().text.contains("<title>You've been blocked</title>")
    }
    !handlerRan

    when:
    TEST_WRITER.waitForTraces(1)
    def trace = TEST_WRITER.get(0)

    then:
    trace.size() == 1
    trace[0].tags['http.status_code'] == 418
    trace[0].tags['appsec.blocked'] == 'true'

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    expectedJson | acceptHeader
    true         | null
    false        | 'text/html;q=0.9, application/json;q=0.8'
    true         | 'text/html;q=0.8, application/json;q=0.9'
  }

  @Flaky(value = "https://github.com/DataDog/dd-trace-java/issues/4681", suites = ["GrizzlyAsyncTest", "GrizzlyTest"])
  def 'test blocking of request with json response'() {
    setup:
    assumeTrue(testBlocking())

    def request = request(SUCCESS, 'GET', null)
      .addHeader(IG_BLOCK_HEADER, 'json')
      .addHeader('Accept', 'text/html')  // preference for html will be ignored
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.code() == 418
    response.header('Content-type') =~ /(?i)\Aapplication\/json(?:;\s?charset=utf-8)?\z/
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    !handlerRan

    when:
    TEST_WRITER.waitForTraces(1)
    def trace = TEST_WRITER.get(0)

    then:
    trace.size() == 1
    trace[0].tags['http.status_code'] == 418
    trace[0].tags['appsec.blocked'] == 'true'

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }


  @Flaky(value = "https://github.com/DataDog/dd-trace-java/issues/4681", suites = ["GrizzlyAsyncTest", "GrizzlyTest"])
  def 'test blocking of request with redirect response'() {
    setup:
    assumeTrue(testBlocking())

    def request = request(SUCCESS, 'GET', null)
      .addHeader(IG_BLOCK_HEADER, 'none').build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.code() == 301
    response.header('location') == 'https://www.google.com/'
    !handlerRan

    when:
    TEST_WRITER.waitForTraces(1)
    def trace = TEST_WRITER.get(0)

    then:
    trace.size() == 1
    trace[0].tags['http.status_code'] == 301
    trace[0].tags['appsec.blocked'] == 'true'

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def "test bad url not cause span marked as error"() {
    setup:
    assumeTrue(testBadUrl())

    when:
    def request = new okhttp3.Request.Builder()
      .url(address.toString() + "success?file=\\abc")
      .get()
      .build()
    client.newCall(request).execute()

    then:
    TEST_WRITER.waitForTraces(1)
    def trace = TEST_WRITER.get(0)
    assert trace.find {it.isError() } == null
  }

  def 'test blocking of request for path parameters'() {
    setup:
    assumeTrue(testBlocking())
    assumeTrue(testPathParam() != null)

    def request = request(PATH_PARAM, 'GET', null)
      .header(IG_PARAMETERS_BLOCK_HEADER, 'true')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }
    response.code() == 413
    response.header('Content-type') =~ /(?i)\Aapplication\/json(?:;\s?charset=utf-8)?\z/
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    !handlerRan
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.flatten().find { DDSpan it ->
      it.tags['http.status_code'] == 413
    } != null
    TEST_WRITER.flatten().find { DDSpan it ->
      it.tags['appsec.blocked'] == 'true'
    } != null

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
  }

  def 'test blocking of request for request body variant #variant'() {
    setup:
    assumeTrue(testUserBlocking())
    assumeTrue(executeTest)

    def request = request(
      endpoint, 'POST',
      RequestBody.create(MediaType.get(contentType), body))
      .header(header, 'true')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }
    response.code() == 413
    // we block after having already requested the writer; in some versions of jetty,
    // the charset can't be changed from iso-8859-1 then
    response.header('Content-type') =~ /(?i)\Aapplication\/json(?:;\s?charset=(?:utf-8|iso-8859-1))?\z/

    def text = response.body().charStream().text
    text.contains('"title":"You\'ve been blocked"')
    text.getBytes(UTF_8).length == BlockingActionHelper.getTemplate(JSON).length

    !handlerRan

    TEST_WRITER.waitForTraces(1)

    then:
    List<DDSpan> spans = TEST_WRITER.flatten()
    spans.find { it.tags['http.status_code'] == 413 } != null
    spans.find { it.tags['appsec.blocked'] == 'true' } != null
    spans.find {
      it.error &&
        it.tags['error.type'] == BlockingException.name } != null

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    variant         | executeTest                | endpoint        | header                   | contentType                         | body
    'plain text'    | testRequestBody()          | CREATED         | headerForPlainTextBody() | 'text/plain'                        | 'my body'
    'plain text IS' | testRequestBodyISVariant() | CREATED_IS      | headerForPlainTextBody() | 'text/plain'                        | 'my body'
    'urlencoded'    | testBodyUrlencoded()       | BODY_URLENCODED | IG_BODY_CONVERTED_HEADER | 'application/x-www-form-urlencoded' | 'a=x'
    'multipart'     | testBodyMultipart()        | BODY_MULTIPART  | IG_BODY_CONVERTED_HEADER | MULTIPART_CONTENT_TYPE              | MULTIPART_BODY
    'json'          | testBodyJson()             | BODY_JSON       | IG_BODY_CONVERTED_HEADER | 'application/json'                  | '{"a": "x"}'
  }

  private final static String MULTIPART_CONTENT_TYPE = 'multipart/form-data; charset=utf-8; boundary=------------------------943d3207457896a3'
  private final static String MULTIPART_BODY =
  '--------------------------943d3207457896a3\r\n' +
  'Content-Disposition: form-data; name="a"\r\n' +
  '\r\n' +
  'x\r\n' +
  '--------------------------943d3207457896a3--'

  private String headerForPlainTextBody() {
    requestBodyNoStreaming ? IG_BODY_CONVERTED_HEADER : IG_BODY_END_BLOCK_HEADER
  }

  def 'user blocking'() {
    setup:
    assumeTrue(testUserBlocking())
    BlockingService origBlockingService = Blocking.SERVICE
    BlockingService bs = new BlockingService() {
        @Override
        BlockingDetails shouldBlockUser(@Nonnull String userId) {
          userId == 'user-to-block' ?
            new BlockingDetails(403, BlockingContentType.JSON, ['X-Header': 'X-Header-Value']) :
            null
        }

        @Override
        boolean tryCommitBlockingResponse(int statusCode, @Nonnull BlockingContentType type,
          @Nonnull Map<String, String> extraHeaders) {
          RequestContext reqCtx = AgentTracer.get().activeSpan().requestContext
          if (reqCtx == null) {
            return false
          }

          BlockResponseFunction blockResponseFunction = reqCtx.blockResponseFunction
          if (blockResponseFunction == null) {
            throw new UnsupportedOperationException("Do not know how to commit blocking response for this server")
          }
          blockResponseFunction.tryCommitBlockingResponse(reqCtx.traceSegment, statusCode, type, extraHeaders)
        }
      }
    Blocking.blockingService = bs

    def testRequest = request(USER_BLOCK, 'GET', null)
      .addHeader('Accept', 'application/json')
      .build()
    def response = client.newCall(testRequest).execute()

    expect:
    response.code() == USER_BLOCK.status
    response.header('Content-type') =~ /(?i)\Aapplication\/json(?:;\s?charset=utf-8)?\z/
    response.header('X-Header') == 'X-Header-Value'
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    !handlerRan

    when:
    TEST_WRITER.waitForTraces(1)
    def trace = TEST_WRITER.get(0)

    then: 'there is an error span'
    trace.find { span ->
      def errorMsg = span.getTag(DDTags.ERROR_MSG)
      if (!errorMsg) {
        return false
      }
      "Blocking user with id 'user-to-block'" in errorMsg
    } != null
    trace.find { span ->
      span.getTag('appsec.blocked') == 'true'
    } != null

    and: 'there is a span with status code 403'
    trace.find { span ->
      span.httpStatusCode == 403
    } != null

    cleanup:
    Blocking.blockingService = origBlockingService
  }

  def 'test blocking on response'() {
    setup:
    assumeTrue(testBlockingOnResponse())

    def request = request(SUCCESS, 'GET', null)
      .header(IG_BLOCK_RESPONSE_HEADER, 'json')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }
    response.code() == 413
    response.header('Content-type') =~ /(?i)\Aapplication\/json(?:;\s?charset=utf-8)?\z/
    response.header(IG_RESPONSE_HEADER) == null // the header should've been cleared
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.flatten().find { DDSpan it ->
      it.tags['http.status_code'] == 413
    } != null
    TEST_WRITER.flatten().find { DDSpan it ->
      it.tags['appsec.blocked'] == 'true'
    } != null
  }


  def 'test blocking on response — redirect variant'() {
    setup:
    assumeTrue(testBlockingOnResponse())

    def request = request(SUCCESS, 'GET', null)
      .header(IG_BLOCK_RESPONSE_HEADER, 'none')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }
    response.code() == 301
    response.header("Location") == 'https://www.google.com/'
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.flatten().find { DDSpan it ->
      it.tags['http.status_code'] == 301
    } != null
    TEST_WRITER.flatten().find { DDSpan it ->
      it.tags['appsec.blocked'] == 'true'
    } != null
  }

  void controllerSpan(TraceAssert trace, ServerEndpoint endpoint = null) {
    def exception = endpoint == CUSTOM_EXCEPTION ? expectedCustomExceptionType() : expectedExceptionType()
    def errorMessage = endpoint?.body
    trace.span {
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
    }
  }

  Class<? extends Exception> expectedExceptionType() {
    return Exception
  }

  Class<? extends Exception> expectedCustomExceptionType() {
    return InputMismatchException
  }

  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    throw new UnsupportedOperationException("handlerSpan not implemented in " + getClass().name)
  }

  void responseSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    throw new UnsupportedOperationException("responseSpan not implemented in " + getClass().name)
  }

  // If you ever feel the need to make this method non final and override it with something that is almost the
  // same, but has a slightly different behavior, then please think again, and see if you can't make that part
  // of the integrations very special behavior into something configurable here instead.
  final void serverSpan(TraceAssert trace,
    BigInteger traceID = null,
    BigInteger parentID = null,
    String method = "GET",
    ServerEndpoint endpoint = SUCCESS,
    Map<String, Serializable> extraTags = null,
    String clientIp = null) {
    Object expectedServerSpanRoute = expectedServerSpanRoute(endpoint)
    Map<String, Serializable> expectedExtraErrorInformation = hasExtraErrorInformation() ? expectedExtraErrorInformation(endpoint) : null
    boolean hasPeerInformation = hasPeerInformation()
    boolean hasPeerPort = hasPeerPort()
    boolean hasForwardedIP = hasForwardedIP()
    def expectedExtraServerTags = expectedExtraServerTags(endpoint)
    def expectedStatus = expectedStatus(endpoint)
    def expectedQueryTag = expectedQueryTag(endpoint)
    def expectedUrl = expectedUrl(endpoint, address)
    trace.span {
      serviceName expectedServiceName()
      operationName operation()
      resourceName expectedResourceName(endpoint, method, address)
      spanType DDSpanTypes.HTTP_SERVER
      errored expectedErrored(endpoint)
      if (parentID != null) {
        traceId traceID
        parentSpanId parentID
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        if (hasPeerInformation) {
          if (hasPeerPort) {
            "$Tags.PEER_PORT" Integer
          }
          "$Tags.PEER_HOST_IPV4" { it == "127.0.0.1" || (endpoint == FORWARDED && it == endpoint.body) }
          "$Tags.HTTP_CLIENT_IP" { it == "127.0.0.1" || (endpoint == FORWARDED && it == endpoint.body) }
        } else {
          "$Tags.HTTP_CLIENT_IP" clientIp
        }
        "$Tags.HTTP_HOSTNAME" address.host
        "$Tags.HTTP_URL" "$expectedUrl"
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_STATUS" expectedStatus
        "$Tags.HTTP_USER_AGENT" String
        if (endpoint == FORWARDED && hasForwardedIP) {
          "$Tags.HTTP_FORWARDED_IP" endpoint.body
        }
        if (null != expectedServerSpanRoute) {
          "$Tags.HTTP_ROUTE" expectedServerSpanRoute
        }
        if (null != expectedExtraErrorInformation) {
          addTags(expectedExtraErrorInformation)
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" expectedQueryTag
        }
        if ({ isDataStreamsEnabled() }) {
          "$DDTags.PATHWAY_HASH" { String }
        }
        // OkHttp never sends the fragment in the request.
        //        if (endpoint.fragment) {
        //          "$DDTags.HTTP_FRAGMENT" endpoint.fragment
        //        }
        defaultTags(true)
        addTags(expectedExtraServerTags)
        if (extraTags) {
          it.addTags(extraTags)
        }
      }
    }
  }

  protected void trailingSpans(TraceAssert traceAssert, ServerEndpoint serverEndpoint) {
  }

  static final String IG_EXTRA_SPAN_NAME_HEADER = "x-ig-write-tags"
  static final String IG_TEST_HEADER = "x-ig-test-header"
  static final String IG_BLOCK_HEADER = "x-block"
  static final String IG_BLOCK_RESPONSE_HEADER = "x-block-response"
  static final String IG_PARAMETERS_BLOCK_HEADER = "x-block-parameters"
  static final String IG_BODY_END_BLOCK_HEADER = "x-block-body-end"
  static final String IG_BODY_CONVERTED_HEADER = "x-block-body-converted"
  static final String IG_PEER_ADDRESS = "ig-peer-address"
  static final String IG_PEER_PORT = "ig-peer-port"
  static final String IG_RESPONSE_STATUS = "ig-response-status"
  static final String IG_RESPONSE_HEADER = "x-ig-response-header"
  static final String IG_RESPONSE_HEADER_VALUE = "ig-response-header-value"
  static final String IG_RESPONSE_HEADER_TAG = "ig-response-header"
  static final String IG_PATH_PARAMS_TAG = "ig-path-params"

  class IGCallbacks {
    static class Context {
      String matchingHeaderValue
      String doneHeaderValue
      String extraSpanName
      HashMap<String, String> tags = new HashMap<>()
      StoredBodySupplier requestBodySupplier
      String igResponseHeaderValue
      String blockingContentType
      boolean parametersBlock
      String responseBlock
      boolean bodyEndBlock
      boolean bodyConvertedBlock
    }

    static final String stringOrEmpty(String string) {
      string == null ? "" : string
    }

    final Supplier<Flow<Context>> requestStartedCb =
    ({
      ->
      new Flow.ResultFlow<Context>(new Context())
    } as Supplier<Flow<Context>>)

    final BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCb =
    ({ RequestContext rqCtxt, IGSpanInfo info ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      if (context.extraSpanName) {
        runUnderTrace(context.extraSpanName, false) {
          def span = activeSpan()
          context.tags.each { key, val ->
            span.setTag(key, val)
          }
        }
      }
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, IGSpanInfo, Flow<Void>>)

    final TriConsumer<RequestContext, String, String> requestHeaderCb =
    { RequestContext rqCtxt, String key, String value ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      if (IG_TEST_HEADER.equalsIgnoreCase(key)) {
        context.matchingHeaderValue = stringOrEmpty(context.matchingHeaderValue) + value
      }
      if (IG_EXTRA_SPAN_NAME_HEADER.equalsIgnoreCase(key)) {
        context.extraSpanName = value
      }
      if (IG_BLOCK_HEADER.equalsIgnoreCase(key)) {
        context.blockingContentType = value
      }
      if (IG_BLOCK_RESPONSE_HEADER.equalsIgnoreCase(key)) {
        context.responseBlock = value
      }
      if (IG_PARAMETERS_BLOCK_HEADER.equalsIgnoreCase(key)) {
        context.parametersBlock = true
      }
      if (IG_BODY_END_BLOCK_HEADER.equalsIgnoreCase(key)) {
        context.bodyEndBlock = true
      }
      if (IG_BODY_CONVERTED_HEADER.equalsIgnoreCase(key)) {
        context.bodyConvertedBlock = true
      }
    } as TriConsumer<RequestContext, String, String>

    final Function<RequestContext, Flow<Void>> requestHeaderDoneCb =
    ({ RequestContext rqCtxt ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      if (null != context.matchingHeaderValue) {
        context.doneHeaderValue = stringOrEmpty(context.doneHeaderValue) + context.matchingHeaderValue
      }

      if (context.blockingContentType && context.blockingContentType != 'none') {
        new RbaFlow(
          new Flow.Action.RequestBlockingAction(418,
          BlockingContentType.valueOf(context.blockingContentType.toUpperCase(Locale.ROOT))))
      } else if (context.blockingContentType && context.blockingContentType == 'none') {
        new RbaFlow(
          Flow.Action.RequestBlockingAction.forRedirect(301, 'https://www.google.com/'))
      } else {
        Flow.ResultFlow.empty()
      }
    } as Function<RequestContext, Flow<Void>>)

    private static final String EXPECTED = "${QUERY_ENCODED_BOTH.rawPath}?${QUERY_ENCODED_BOTH.rawQuery}"

    final TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> requestUriRawCb =
    ({ RequestContext rqCtxt, String method, URIDataAdapter uri ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      String raw = uri.supportsRaw() ? uri.raw() : ''
      raw = uri.hasPlusEncodedSpaces() ? raw.replace('+', '%20') : raw
      // Only trigger for query path without query parameters and with special header
      if (raw.endsWith(EXPECTED) && context.doneHeaderValue) {
        context.extraSpanName = stringOrEmpty(context.extraSpanName) + "$IG_TEST_HEADER-${context.doneHeaderValue}"
        // Only do this for the first time since some instrumentations with handler spans may call
        // DECORATE.onRequest multiple times
        context.doneHeaderValue = null
      }
      Flow.ResultFlow.empty()
    } as TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>)

    final TriFunction<RequestContext, String, Integer, Flow<Void>> requestClientSocketAddressCb =
    ({ RequestContext rqCtxt, String address, Integer port ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      context.tags.put(IG_PEER_ADDRESS, address)
      context.tags.put(IG_PEER_PORT, String.valueOf(port))
      Flow.ResultFlow.empty()
    } as TriFunction<RequestContext, String, Integer, Flow<Void>>)

    final BiFunction<RequestContext, StoredBodySupplier, Void> requestBodyStartCb =
    { RequestContext rqCtxt, StoredBodySupplier supplier ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      context.requestBodySupplier = supplier
      null
    } as BiFunction<RequestContext, StoredBodySupplier, Void>

    final BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestBodyEndCb =
    ({ RequestContext rqCtxt, StoredBodySupplier supplier ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      if (!context.requestBodySupplier.is(supplier)) {
        throw new RuntimeException("Expected same instance: ${context.requestBodySupplier} and $supplier")
      }
      activeSpan().localRootSpan.setTag('request.body', supplier.get() as String)
      if (context.bodyEndBlock) {
        new RbaFlow(
          new Flow.Action.RequestBlockingAction(413, BlockingContentType.JSON)
          )
      } else {
        Flow.ResultFlow.empty()
      }
    } as BiFunction<RequestContext, StoredBodySupplier, Flow<Void>>)

    final BiFunction<RequestContext, Object, Flow<Void>> requestBodyObjectCb =
    ({ RequestContext rqCtxt, Object obj ->
      if (obj instanceof Map) {
        obj = obj.collectEntries {
          [
            it.key,
            (it.value instanceof Iterable || it.value instanceof String[]) ? it.value : [it.value]
          ]}
      } else if (!(obj instanceof String)) {
        obj = obj.properties
          .findAll { it.key != 'class' }
          .collectEntries { [it.key, it.value instanceof Iterable ? it.value : [it.value]] }
      }
      rqCtxt.traceSegment.setTagTop('request.body.converted', obj as String)
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      if (context.bodyConvertedBlock) {
        new RbaFlow(
          new Flow.Action.RequestBlockingAction(413, BlockingContentType.JSON)
          )
      } else {
        Flow.ResultFlow.empty()
      }
    } as BiFunction<RequestContext, Object, Flow<Void>>)

    final BiFunction<RequestContext, Integer, Flow<Void>> responseStartedCb =
    ({ RequestContext rqCtxt, Integer resultCode ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      context.tags.put(IG_RESPONSE_STATUS, String.valueOf(resultCode))
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, Integer, Flow<Void>>)

    final TriConsumer<RequestContext, String, String> responseHeaderCb =
    { RequestContext rqCtxt, String key, String value ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      if (IG_RESPONSE_HEADER.equalsIgnoreCase(key)) {
        context.igResponseHeaderValue = stringOrEmpty(context.igResponseHeaderValue) + value
      }
    } as TriConsumer<RequestContext, String, String>

    final Function<RequestContext, Flow<Void>> responseHeaderDoneCb =
    ({ RequestContext rqCtxt ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      if (null != context.igResponseHeaderValue) {
        context.tags.put(IG_RESPONSE_HEADER_TAG, context.igResponseHeaderValue)
      }
      if (context.responseBlock == 'none') {
        new RbaFlow(
          new Flow.Action.RequestBlockingAction(301, BlockingContentType.NONE,
          [Location: 'https://www.google.com/'])
          )
      } else if (context.responseBlock == 'json') {
        new RbaFlow(
          new Flow.Action.RequestBlockingAction(413, BlockingContentType.JSON)
          )
      } else {
        Flow.ResultFlow.empty()
      }
    } as Function<RequestContext, Flow<Void>>)

    final BiFunction<RequestContext, Map<String, ?>, Flow<Void>> requestParamsCb =
    { RequestContext rqCtxt, Map<String, ?> map ->
      Context context = rqCtxt.getData(RequestContextSlot.APPSEC)
      if (context.parametersBlock) {
        return new RbaFlow(
          new Flow.Action.RequestBlockingAction(413, BlockingContentType.JSON)
          )
      }

      if (map && !map.empty) {
        context.tags.put(IG_PATH_PARAMS_TAG, map)
      }
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, Map<String, ?>, Flow<Void>>
  }

  class IastIGCallbacks {
    static class Context {
    }

    final Supplier<Flow<Context>> requestStartedCb =
    ({
      ->
      new Flow.ResultFlow<Context>(new Context())
    } as Supplier<Flow<Context>>)

    final BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCb =
    ({ RequestContext rqCtxt, IGSpanInfo info ->
      Context context = rqCtxt.getData(RequestContextSlot.IAST)
      assert context != null
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, IGSpanInfo, Flow<Void>>)
  }

  @Canonical
  static class RbaFlow implements Flow<Void> {
    Action.RequestBlockingAction action

    @Override
    Void getResult() {
      null
    }
  }
}
