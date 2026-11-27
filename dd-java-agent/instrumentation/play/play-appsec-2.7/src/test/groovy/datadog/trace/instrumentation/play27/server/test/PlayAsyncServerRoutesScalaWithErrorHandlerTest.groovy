package datadog.trace.instrumentation.play27.server.test

import datadog.context.Context
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingOtelHttpSemantics
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.KnownTags
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.gateway.InferredProxySpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.play26.PlayAdvice
import datadog.trace.instrumentation.play26.PlayHttpServerDecorator
import datadog.trace.instrumentation.play26.server.TestHttpErrorHandler
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import play.api.BuiltInComponents
import play.api.libs.typedmap.TypedMap$
import play.api.mvc.Request as ScalaRequest
import play.api.routing.HandlerDef
import play.libs.concurrent.ClassLoaderExecution
import play.routing.Router
import spock.lang.Shared

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Function

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext

class PlayAsyncServerRoutesScalaWithErrorHandlerTest extends AbstractPlayServer27WithErrorHandlerTest {
  @Shared
  ExecutorService executor

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // otherwise the resource name will be set from the raw path with higher priority.
    // We expect non-raw (decoded) paths in resource names
    // Bug in HttpResourceDecorator.withRoute(AgentSpan, CharSequence, CharSequence, boolean)
    // by not checking value of http.server.raw.resource ?
    injectSysConfig(TraceInstrumentationConfig.HTTP_SERVER_ROUTE_BASED_NAMING, 'false')
  }

  void cleanupSpec() {
    executor.shutdown()
  }

  @Override
  HttpServer server() {
    executor = Executors.newCachedThreadPool()
    routesServer(executor)
  }

  /** Play server running the compiled {@code routes} file. */
  static HttpServer routesServer(ExecutorService executor) {
    Function<BuiltInComponents, Router> f = { play.api.BuiltInComponentsFromContext it ->
      new router.Routes(
        it.httpErrorHandler(),
        new PlayController(it.controllerComponents(), ClassLoaderExecution.fromThread(executor))
        )
    } as Function<BuiltInComponents, Router>
    new PlayHttpServerScala(f, new TestHttpErrorHandler())
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    [path: '123']
  }

  private String routeFor(HttpServerTest.ServerEndpoint endpoint) {

    switch (endpoint) {
      case PATH_PARAM:
        return '/path/$path<[^/]+>/param'
      case NOT_FOUND:
        return null
      default:
        endpoint.path
    }
  }

  /** Expected {@code _dd.appsec.normalized_route} for the route of the endpoint, or null. */
  private String normalizedRouteFor(HttpServerTest.ServerEndpoint endpoint) {
    if (!Config.get().isApiSecurityEnabled()) {
      return null
    }
    switch (endpoint) {
      case PATH_PARAM:
        return '/path/{path}/param'
      case NOT_FOUND:
        return null
      default:
        return normalizedRouteOf('', endpoint.path)
    }
  }

  @Override
  Serializable expectedNormalizedRoute(HttpServerTest.ServerEndpoint endpoint) {
    Config.get().isTraceOtelSemanticsEnabled() ? normalizedRouteFor(endpoint) : null
  }

  @Override
  Serializable expectedServerSpanRoute(HttpServerTest.ServerEndpoint endpoint) {
    Config.get().isTraceOtelSemanticsEnabled() ? routeFor(endpoint) : null
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(HttpServerTest.ServerEndpoint endpoint) {
    Config.get().isTraceOtelSemanticsEnabled() ? [:] : [(Tags.HTTP_ROUTE): null]
  }

  // we set Tags.HTTP_ROUTE with a routes file; override method for this
  @Override
  void handlerSpan(TraceAssert trace, HttpServerTest.ServerEndpoint endpoint = SUCCESS) {
    def expectedQueryTag = expectedQueryTag(endpoint)
    def expectedRoute = routeFor(endpoint)
    def expectedNormalizedRoute = normalizedRouteFor(endpoint)
    def otelSemantics = Config.get().isTraceOtelSemanticsEnabled()
    def requestUrl = endpoint.resolve(address)
    def expectedPath = Config.get().isHttpServerRawResource() && supportsRaw() ? requestUrl.rawPath : requestUrl.path
    trace.span {
      serviceName expectedServiceName()
      operationName "play.request"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == EXCEPTION || endpoint == CUSTOM_EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" PlayHttpServerDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" '127.0.0.1'
        "$Tags.HTTP_CLIENT_IP" (endpoint == FORWARDED ? endpoint.body : '127.0.0.1')
        "$Tags.NETWORK_CLIENT_IP" '127.0.0.1'
        if (otelSemantics) {
          "$Tags.HTTP_URL" null
          "$Tags.HTTP_HOSTNAME" address.host
          "$KnownTags.SERVER_PORT_NAME" address.port
          "$KnownTags.URL_PATH_NAME" expectedPath
          "$KnownTags.URL_SCHEME_NAME" address.scheme
        } else {
          "$Tags.HTTP_URL" String
          "$Tags.HTTP_HOSTNAME" address.host
        }
        "$Tags.HTTP_METHOD" String
        "$Tags.HTTP_ROUTE" expectedRoute
        "$NORMALIZED_ROUTE_TAG" expectedNormalizedRoute
        if (endpoint == EXCEPTION || endpoint == CUSTOM_EXCEPTION) {
          errorTags(endpoint == CUSTOM_EXCEPTION ? TestHttpErrorHandler.CustomRuntimeException : RuntimeException, endpoint.body)
        }
        if (endpoint.query) {
          tag(otelSemantics ? KnownTags.URL_QUERY_NAME : DDTags.HTTP_QUERY, expectedQueryTag)
        }
        defaultTags()
      }
    }
  }

  private Request getRequest(String encodedPath) {
    // encodedPath keeps empty segments, which URI.resolve would drop
    def url = HttpUrl.get(address).newBuilder().encodedPath(encodedPath).build()
    new Request.Builder().url(url).get().build()
  }

  def "normalized route for #path"() {
    setup:
    // Warm up the route with a well-formed request first: PATH_CACHE keeps the http.route that
    // addMissingSlash computes for the first request of a route (pre-existing behavior, tracked as
    // a follow-up), so a malformed first request would change it and make rows order-dependent.
    def warmUp = client.newCall(getRequest(warmUpPath)).execute()
    warmUp.body().string()
    assert warmUp.code() == 200
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()
    def request = getRequest(path)
    def otelSemantics = Config.get().isTraceOtelSemanticsEnabled()
    def expectedTag = Config.get().isApiSecurityEnabled() ? normalized : null

    when:
    def response = client.newCall(request).execute()
    def body = response.body().string()
    TEST_WRITER.waitForTraces(1)
    def spans = TEST_WRITER.flatten()
    DDSpan play = spans.find { it.operationName.toString() == 'play.request' }
    DDSpan server = spans.find { it.spanId == play.parentId }

    then:
    response.code() == 200
    body == expectedBody
    play.getTag(Tags.HTTP_ROUTE)?.toString() == route
    play.getTag(NORMALIZED_ROUTE_TAG)?.toString() == expectedTag
    server.getTag(Tags.HTTP_ROUTE)?.toString() == (otelSemantics ? route : null)
    server.getTag(NORMALIZED_ROUTE_TAG)?.toString() == (otelSemantics ? expectedTag : null)

    where:
    path                   | warmUpPath        | expectedBody | route                             | normalized
    '/catch-all/a/b/c.txt' | '/catch-all/warm' | 'a/b/c.txt'  | '/catch-all/$path<.+>'            | '/catch-all/{path}'
    '/catch-all//'         | '/catch-all/warm' | '/'          | '/catch-all/$path<.+>'            | '/catch-all/'
    '/files/report.pdf'    | '/files/warm.up'  | 'report.pdf' | '/files/$name<[^/]+>.$ext<[^/]+>' | '/files/{name}.{ext}'
    '/opt/abc/end'         | '/opt/warm/end'   | 'abc'        | '/opt/$x<[^/]*>/end'              | '/opt/{x}/end'
    '/opt//end'            | '/opt/warm/end'   | ''           | '/opt/$x<[^/]*>/end'              | '/opt/end'
  }
}

class PlayAsyncServerRoutesOtelSemanticsForkedTest extends PlayAsyncServerRoutesScalaWithErrorHandlerTest implements TestingOtelHttpSemantics {}

/**
 * With API Security disabled no span gets the normalized route and {@code http.route} is unchanged.
 * A few requests against the routes app, rather than the whole inherited suite.
 */
class PlayAsyncServerRoutesApiSecurityDisabledForkedTest extends InstrumentationSpecification {
  @Shared
  ExecutorService executor = Executors.newCachedThreadPool()

  @Shared
  HttpServer server

  @Shared
  OkHttpClient client = OkHttpUtils.client()

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(AppSecConfig.API_SECURITY_ENABLED, 'false')
  }

  void setupSpec() {
    server = PlayAsyncServerRoutesScalaWithErrorHandlerTest.routesServer(executor)
    server.start()
  }

  void cleanupSpec() {
    server?.stop()
    executor.shutdown()
  }

  def "no normalized route for #path"() {
    setup:
    // the assertions below only prove something when the tag would otherwise be set
    assert !Config.get().isApiSecurityEnabled()
    def request = new Request.Builder().url(server.address().resolve(path.substring(1)).toURL()).get().build()

    when:
    def response = client.newCall(request).execute()
    response.body().string()
    TEST_WRITER.waitForTraces(1)
    def spans = TEST_WRITER.flatten()
    DDSpan play = spans.find { it.operationName.toString() == 'play.request' }
    DDSpan serverSpan = spans.find { it.spanId == play.parentId }

    then:
    response.code() == 200
    play.getTag(Tags.HTTP_ROUTE)?.toString() == route
    play.getTag(NORMALIZED_ROUTE_TAG) == null
    serverSpan.getTag(Tags.HTTP_ROUTE) == null
    serverSpan.getTag(NORMALIZED_ROUTE_TAG) == null

    where:
    path              | route
    '/path/123/param' | '/path/$path<[^/]+>/param'
    '/catch-all/a/b'  | '/catch-all/$path<.+>'
  }
}

class PlayRouteTracingFilterOtelSemanticsForkedTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('trace.inferred.proxy.services.enabled', 'true')
    injectSysConfig('trace.otel.semantics.enabled', 'true')
  }

  def "application routes preserve gateway routes and skip tracing filters before sampling"() {
    setup:
    def handler = Stub(HandlerDef) {
      path() >> '/items/{param}'
    }
    def requestAttributes = TypedMap$.MODULE$.empty().updated(Router.Attrs.HANDLER_DEF.asScala(), handler)
    def request = Stub(ScalaRequest) {
      attrs() >> requestAttributes
      method() >> 'GET'
      path() >> '/items/value'
      host() >> 'localhost'
      remoteAddress() >> '127.0.0.1'
      rawQueryString() >> ''
    }
    request.addAttr(_, _) >> request
    def inferred = InferredProxySpan.fromHeaders([
      'x-dd-proxy': 'aws-apigateway',
      'x-dd-proxy-request-time-ms': Long.toString(System.currentTimeMillis()),
      'x-dd-proxy-httpmethod': 'GET',
      'x-dd-proxy-path': '/public/items/value',
      'x-dd-proxy-resource-path': '/public/{proxy+}'
    ])
    def parent = inferredProxy ? Context.root().with(inferred) : Context.root()
    def serverContext = PlayHttpServerDecorator.DECORATE.startSpan(null, parent)
    def server = fromContext(serverContext)
    server.setResourceName('GET')
    def serverScope = serverContext.attach()
    def filter = tracingFilter ? TEST_TRACER.startSpan('test', 'filter1') : null
    filter?.setResourceName('filter1')
    def filterScope = filter ? TEST_TRACER.activateSpan(filter) : null
    def playScope
    def play

    when:
    playScope = PlayAdvice.onEnter(request)
    play = fromContext(playScope.context())

    then:
    play.getTag('http.route') == '/items/{param}'
    server.getTag('http.route') == '/items/{param}'
    server.resourceName.toString() == 'GET /items/{param}'
    play.parentId == (filter ?: server).spanId
    !filter || filter.getTag('http.route') == null
    !filter || filter.resourceName.toString() == 'filter1'
    !inferredProxy || server.parentId == server.localRootSpan.spanId
    !inferredProxy || server.localRootSpan.getTag('http.route') == '/public/{proxy+}'
    !inferredProxy || server.localRootSpan.resourceName.toString() == 'GET /public/{proxy+}'

    cleanup:
    playScope?.close()
    play?.finish()
    filterScope?.close()
    filter?.finish()
    serverScope?.close()
    server?.finish()
    inferred?.finish()

    where:
    inferredProxy | tracingFilter
    false         | false
    false         | true
    true          | false
    true          | true
  }
}
