package server

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator
import datadog.trace.instrumentation.vertx_3_4.server.VertxRouterDecorator
import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject

import java.util.concurrent.CompletableFuture

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static server.VertxTestServer.CONFIG_HTTP_SERVER_PORT

class VertxHttpServerTest extends HttpServerTest<Vertx> {
  @Override
  Vertx startServer(int port) {
    def server = Vertx.vertx(new VertxOptions()
    // Useful for debugging:
    // .setBlockedThreadCheckInterval(Integer.MAX_VALUE)
      .setClusterPort(port))
    final CompletableFuture<Void> future = new CompletableFuture<>()
    server.deployVerticle(verticle().name,
      new DeploymentOptions()
        .setConfig(new JsonObject().put(CONFIG_HTTP_SERVER_PORT, port))
        .setInstances(3)) { res ->
      if (!res.succeeded()) {
        throw new RuntimeException("Cannot deploy server Verticle", res.cause())
      }
      future.complete(null)
    }

    future.get()
    return server
  }

  protected Class<AbstractVerticle> verticle() {
    VertxTestServer
  }

  @Override
  void stopServer(Vertx server) {
    server.close()
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
  boolean testExceptionBody() {
    // Vertx wraps the exception
    false
  }

  boolean testExceptionTag() {
    true
  }

  @Override
  boolean reorderControllerSpan() {
    true
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
        "$Tags.COMPONENT" VertxRouterDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.HTTP_STATUS" Integer
        if (endpoint == EXCEPTION && this.testExceptionTag()) {
          errorTags(Exception, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }
}

class VertxChainingHttpServerTest extends VertxHttpServerTest {
  @Override
  protected Class<AbstractVerticle> verticle() {
    VertxChainingTestServer
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
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.HTTP_STATUS" Integer
        "chain" true
        if (endpoint == EXCEPTION && this.testExceptionTag()) {
          errorTags(Exception, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }
}
