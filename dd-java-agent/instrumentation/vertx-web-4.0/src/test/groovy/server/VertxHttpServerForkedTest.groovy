package server

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator
import datadog.trace.instrumentation.vertx_4_0.server.VertxDecorator
import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.impl.VertxInternal
import io.vertx.core.json.JsonObject

import java.util.concurrent.CompletableFuture

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.LOGIN
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static server.VertxTestServer.CONFIG_HTTP_SERVER_PORT

class VertxHttpServerForkedTest extends HttpServerTest<Vertx> {

  private class VertxServer implements HttpServer {
    private VertxInternal server
    private String routerBasePath
    private port

    VertxServer(String routerBasePath) {
      this.routerBasePath = routerBasePath
    }

    @Override
    void start() {
      server = Vertx.vertx()

      final CompletableFuture<Void> future = new CompletableFuture<>()
      server.eventBus().localConsumer("PORT_DATA")
        .handler({ message ->
          port = message.body()
          message.reply(null)
          future.complete(null)
        })

      server.deployVerticle(verticle().name,
        new DeploymentOptions()
        .setConfig(new JsonObject().put(CONFIG_HTTP_SERVER_PORT, 0))
        .setInstances(1)) { res ->
          if (!res.succeeded()) {
            throw new RuntimeException("Cannot deploy server Verticle", res.cause())
          }
        }
      future.get()
    }

    @Override
    void stop() {
      server.close()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port$routerBasePath")
    }
  }

  @Override
  HttpServer server() {
    return new VertxServer(routerBasePath())
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
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyJson() {
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
