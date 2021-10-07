package server

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.vertx_3_4.server.VertxRouterDecorator
import io.vertx.core.AbstractVerticle

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class VertxSubrouterForkedTest extends VertxHttpServerForkedTest {
  @Override
  protected Class<AbstractVerticle> verticle() {
    VertxSubrouterTestServer
  }

  @Override
  String routerBasePath() {
    return "/sub/"
  }

  @Override
  boolean hasDecodedResource() {
    // copied from HttpServerTest since super overrides it
    return !Config.get().isHttpServerRawResource() || !supportsRaw()
  }

  @Override
  String testPathParam() {
    null
  }

  @Override
  int spanCount(ServerEndpoint endpoint) {
    return 2 + (hasHandlerSpan() ? 1 : 0) + (hasResponseSpan(endpoint) ? 1 : 0)
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "vertx.route-handler"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == ERROR || endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" VertxRouterDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.HTTP_STATUS" Integer
        "chain" true
        if (endpoint == EXCEPTION && this.testExceptionTag()) {
          errorTags(RuntimeException, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }
}
