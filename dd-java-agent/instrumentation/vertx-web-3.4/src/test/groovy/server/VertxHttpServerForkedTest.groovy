package server

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator
import datadog.trace.instrumentation.vertx_3_4.server.VertxRouterDecorator
import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.impl.VertxInternal
import io.vertx.core.json.JsonObject

import java.util.concurrent.CompletableFuture

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static server.VertxTestServer.CONFIG_HTTP_SERVER_PORT

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class VertxHttpServerForkedTest extends HttpServerTest<Vertx> {

  private class VertxServer implements HttpServer {
    private VertxInternal server
    private int port = 0

    @Override
    void start() {
      server = Vertx.vertx(new VertxOptions()
        // Useful for debugging:
        // .setBlockedThreadCheckInterval(Integer.MAX_VALUE)
        .setClusterPort(0))
      final CompletableFuture<Void> future = new CompletableFuture<>()
      server.deployVerticle(verticle().name,
        new DeploymentOptions()
        .setConfig(new JsonObject().put(CONFIG_HTTP_SERVER_PORT, port))
        .setInstances(1)) { res ->
          if (!res.succeeded()) {
            throw new RuntimeException("Cannot deploy server Verticle", res.cause())
          }
          future.complete(null)
        }
      future.get()
      port = server.sharedHttpServers().values().first().actualPort()
    }

    @Override
    void stop() {
      server.close()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }
  }

  @Override
  HttpServer server() {
    return new VertxServer()
  }

  protected Class<AbstractVerticle> verticle() {
    VertxTestServer
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
    "/path/:id/param"
  }

  @Override
  boolean testExceptionBody() {
    // Vertx wraps the exception
    false
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
        "$Tags.HTTP_STATUS" Integer
        if (endpoint == EXCEPTION && this.testExceptionTag()) {
          errorTags(RuntimeException, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }

  @Override
  def setup() {
    // Vertx + netty instrumentations produce overlapping spans
    // Disable the checkpoint interval validation
    CheckpointValidator.excludeValidations(EnumSet.of(CheckpointValidationMode.INTERVALS))
  }
}

@spock.lang.IgnoreIf({ datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest() })
class VertxChainingHttpServerForkedTest extends VertxHttpServerForkedTest {
  @Override
  protected Class<AbstractVerticle> verticle() {
    VertxChainingTestServer
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
