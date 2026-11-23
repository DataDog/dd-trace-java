package server

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingOtelHttpSemantics
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.KnownTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator
import datadog.trace.instrumentation.ratpack.RatpackServerDecorator
import ratpack.test.embed.EmbeddedApp

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class RatpackHttpServerTest extends HttpServerTest<EmbeddedApp> {

  @Override
  HttpServer server() {
    return new RatpackServer(SyncRatpackApp.INSTANCE)
  }

  @Override
  String component() {
    return NettyHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    "netty.request"
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    [id: '123']
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBodyFilenames() {
    true
  }

  @Override
  boolean testBodyJson() {
    true
  }

  @Override
  boolean testResponseBodyJson() {
    true
  }

  @Override
  String testPathParam() {
    '/path/:id/param'
  }

  @Override
  boolean testNotFound() {
    // resource name is set by instrumentation, so not changed to 404
    false
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  boolean testBlockFailure() {
    true
  }

  @Override
  BlockFailureVariant blockFailureVariant() {
    // Ratpack's own blocking call site for request headers lives in the Netty instrumentation;
    // path params are published by PathBindingPublishingHandler, a Ratpack-specific call site.
    BlockFailureVariant.PATH_PARAMS
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    return endpoint == PATH_PARAM ? testPathParam() : endpoint.rawPath
  }

  @Override
  boolean hasDecodedResource() {
    false
  }

  @Override
  boolean testMultipleHeader() {
    // @Flaky("https://github.com/DataDog/dd-trace-java/issues/3867")
    true
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    def otelSemantics = Config.get().isTraceOtelSemanticsEnabled()
    def requestUrl = endpoint.resolve(address)
    def expectedRoute = expectedServerSpanRoute(endpoint)
    trace.span {
      serviceName expectedServiceName()
      operationName "ratpack.handler"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == ERROR || endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" RatpackServerDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" "127.0.0.1" // This span ignores "x-forwards-from".
        "$Tags.PEER_PORT" Integer
        if (otelSemantics) {
          "$Tags.HTTP_URL" null
          "$Tags.HTTP_HOSTNAME" address.host
          "$KnownTags.SERVER_PORT_NAME" address.port
          "$KnownTags.URL_PATH_NAME" requestUrl.rawPath
          "$KnownTags.URL_SCHEME_NAME" address.scheme
        } else {
          "$Tags.HTTP_URL" String
          "$Tags.HTTP_HOSTNAME" "${address.host}"
        }
        "$Tags.HTTP_METHOD" String
        "$Tags.HTTP_STATUS" Integer
        "$Tags.HTTP_ROUTE" expectedRoute
        "$Tags.HTTP_CLIENT_IP" (endpoint == FORWARDED ? endpoint.body : '127.0.0.1')
        "$Tags.NETWORK_CLIENT_IP" '127.0.0.1'
        if (endpoint == EXCEPTION) {
          errorTags(Exception, EXCEPTION.body)
        } else if (otelSemantics && endpoint == ERROR) {
          "$DDTags.ERROR_TYPE" endpoint.status.toString()
        }
        if (endpoint.query) {
          tag(otelSemantics ? KnownTags.URL_QUERY_NAME : DDTags.HTTP_QUERY, endpoint.rawQuery)
        }
        defaultTags()
      }
    }
  }
}

class RatpackHttpServerOtelSemanticsForkedTest extends RatpackHttpServerTest implements TestingOtelHttpSemantics {
}
