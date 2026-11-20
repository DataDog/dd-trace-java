package datadog.trace.instrumentation.play25.server

import datadog.context.Context
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.gateway.InferredProxySpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.play24.PlayAdvice
import datadog.trace.instrumentation.play24.PlayHttpServerDecorator
import datadog.trace.instrumentation.play25.PlayController
import datadog.trace.test.util.Flaky
import groovy.transform.CompileStatic
import play.api.mvc.Request
import scala.concurrent.ExecutionContext$
import spock.lang.Shared

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext

class PlayScalaRoutesServerTest extends PlayServerTest {
  @Shared
  ExecutorService executor

  def cleanupSpec() {
    executor.shutdown()
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.HTTP_SERVER_ROUTE_BASED_NAMING, 'false')
  }

  @Override
  @CompileStatic
  HttpServer server() {
    executor = Executors.newCachedThreadPool()

    def router = new router.Routes(
      new play.core.j.JavaHttpErrorHandlerAdapter(TestHttpErrorHandler.INSTANCE),
      new PlayController(ExecutionContext$.MODULE$.fromExecutor(executor))
      )
    new PlayHttpServer(router.asJava())
  }

  @Override
  String testPathParam() {
    '/path/?/param'
  }

  @Flaky("https://github.com/DataDog/dd-trace-java/issues/6952")
  @Override
  boolean testUserBlocking() {
    "false" != System.getProperty("run.flaky.tests") // Set when using -PskipFlakyTests gradle parameter
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
        endpoint.rawPath
    }
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(HttpServerTest.ServerEndpoint endpoint) {
    [(Tags.HTTP_ROUTE): routeFor(endpoint)]
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  void handlerSpan(TraceAssert trace, HttpServerTest.ServerEndpoint endpoint = SUCCESS) {
    def expectedQueryTag = expectedQueryTag(endpoint)
    trace.span {
      serviceName expectedServiceName()
      operationName 'play.request'
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == EXCEPTION || endpoint == CUSTOM_EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" 'play-action'
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" '127.0.0.1'
        "$Tags.HTTP_CLIENT_IP" (endpoint == FORWARDED ? endpoint.body : '127.0.0.1')
        "$Tags.NETWORK_CLIENT_IP" '127.0.0.1'
        "$Tags.HTTP_URL" String
        "$Tags.HTTP_HOSTNAME" address.host
        "$Tags.HTTP_METHOD" String
        "$Tags.HTTP_ROUTE" this.routeFor(endpoint)
        if (endpoint == EXCEPTION || endpoint == CUSTOM_EXCEPTION) {
          errorTags(endpoint == CUSTOM_EXCEPTION ? TestHttpErrorHandler.CustomRuntimeException : RuntimeException, endpoint.body)
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" expectedQueryTag
        }
        defaultTags()
      }
    }
  }
}

class PlayRouteTracingFilterOtelSemanticsForkedTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('trace.inferred.proxy.services.enabled', 'true')
    injectSysConfig('trace.otel.semantics.enabled', 'true')
  }

  def "tracing filters do not receive the HTTP server route before sampling"() {
    setup:
    def routeTags = scala.collection.immutable.Map$.MODULE$.empty().updated('ROUTE_PATTERN', '/items/{param}')
    def request = Stub(Request) {
      tags() >> routeTags
      method() >> 'GET'
      path() >> '/items/value'
      host() >> 'localhost'
      remoteAddress() >> '127.0.0.1'
      rawQueryString() >> ''
    }
    request.withTag(_, _) >> request
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
    def filter = TEST_TRACER.startSpan('test', 'filter1')
    filter.setResourceName('filter1')
    def filterScope = TEST_TRACER.activateSpan(filter)
    def playScope
    def play

    when:
    playScope = PlayAdvice.onEnter(request)
    play = fromContext(playScope.context())

    then:
    play.getTag('http.route') == '/items/{param}'
    server.getTag('http.route') == '/items/{param}'
    server.resourceName.toString() == 'GET /items/{param}'
    filter.getTag('http.route') == null
    filter.resourceName.toString() == 'filter1'
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
    inferredProxy << [false, true]
  }
}
