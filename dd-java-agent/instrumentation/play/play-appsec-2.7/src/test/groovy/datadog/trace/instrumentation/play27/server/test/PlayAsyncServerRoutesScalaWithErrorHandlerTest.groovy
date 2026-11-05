package datadog.trace.instrumentation.play27.server.test

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingOtelHttpSemantics
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.KnownTags
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.play26.PlayHttpServerDecorator
import datadog.trace.instrumentation.play26.server.TestHttpErrorHandler
import okhttp3.Request
import play.api.BuiltInComponents
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
}

class PlayAsyncServerRoutesOtelSemanticsForkedTest extends PlayAsyncServerRoutesScalaWithErrorHandlerTest implements TestingOtelHttpSemantics {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('trace.inferred.proxy.services.enabled', 'true')
  }

  def "application routes preserve the inferred gateway route"() {
    when:
    def response = client.newCall(new Request.Builder()
      .url(address.resolve('/path/123/param').toString())
      .header('x-dd-proxy', 'aws-apigateway')
      .header('x-dd-proxy-request-time-ms', Long.toString(System.currentTimeMillis()))
      .header('x-dd-proxy-httpmethod', 'GET')
      .header('x-dd-proxy-path', '/public/path/123/param')
      .header('x-dd-proxy-resource-path', '/public/{proxy+}')
      .build()).execute()
    assert response.code() == 200
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    def spans = TEST_WRITER.flatten()
    def gateway = spans.find { it.getTag('_dd.inferred_span') != null }
    def transport = spans.find { it.getTag('component')?.toString() == 'akka-http-server' }
    def play = spans.find { it.getTag('component')?.toString() == 'play-action' }
    gateway != null
    transport != null
    play != null
    transport.parentId == gateway.spanId
    gateway.getTag('http.route') == '/public/{proxy+}'
    gateway.resourceName.toString() == 'GET /public/{proxy+}'
    transport.getTag('http.route') == '/path/$path<[^/]+>/param'
    transport.resourceName.toString() == 'GET /path/$path<[^/]+>/param'
    play.getTag('http.route') == '/path/$path<[^/]+>/param'
  }
}
