package server

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.LOGIN
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator
import datadog.trace.instrumentation.vertx_4_0.server.VertxDecorator
import io.vertx.core.AbstractVerticle
import io.vertx.core.Vertx

class VertxHttpServerForkedTest extends HttpServerTest<Vertx> {
  @Override
  HttpServer server() {
    return new VertxServer(verticle(), routerBasePath())
  }

  protected Class<AbstractVerticle> verticle() {
    VertxTestServer
  }

  String routerBasePath() {
    return "/"
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
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  String testPathParam() {
    routerBasePath() + "path/:id/param"
  }

  @Override
  boolean testExceptionBody() {
    // Vertx wraps the exception
    false
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    [id: '123']
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testResponseBodyJson() {
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
  boolean testBodyJson() {
    true
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
  boolean isRequestBodyNoStreaming() {
    true
  }

  @Override
  Class<? extends Exception> expectedExceptionType() {
    return RuntimeException
  }

  boolean testExceptionTag() {
    true
  }

  @Override
  boolean hasDecodedResource() {
    return false
  }

  @Override
  int spanCount(ServerEndpoint endpoint) {
    if (endpoint == NOT_FOUND) {
      return super.spanCount(endpoint) - 1
    }
    return super.spanCount(endpoint)
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  boolean testSessionId() {
    true
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    switch (endpoint) {
      case LOGIN:
      case NOT_FOUND:
        return null
      case PATH_PARAM:
        return testPathParam()
      default:
        return routerBasePath() + endpoint.relativePath()
    }
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    if (endpoint == NOT_FOUND) {
      return
    }
    trace.span {
      serviceName expectedServiceName()
      operationName "vertx.route-handler"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == ERROR || endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" VertxDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.HTTP_STATUS" Integer
        if (endpoint == EXCEPTION && this.testExceptionTag()) {
          errorTags(RuntimeException, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }
}

class VertxHttpServerWorkerForkedTest extends VertxHttpServerForkedTest {
  @Override
  HttpServer server() {
    return new VertxServer(verticle(), routerBasePath(), true)
  }
}
