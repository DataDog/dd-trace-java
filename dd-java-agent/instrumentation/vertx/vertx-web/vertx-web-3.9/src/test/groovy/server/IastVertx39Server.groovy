package server

import com.datadog.iast.test.IastHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.impl.VertxInternal
import io.vertx.core.json.JsonObject

import java.util.concurrent.CompletableFuture

class IastVertx39Server extends IastHttpServerTest<Vertx> {

  private class VertxServer implements HttpServer {
    private VertxInternal server
    private int port = 0
    private String routerBasePath

    VertxServer(String routerBasePath) {
      this.routerBasePath = routerBasePath
    }

    @Override
    void start() {
      server = Vertx.vertx(new VertxOptions()
        // Useful for debugging:
        // .setBlockedThreadCheckInterval(Integer.MAX_VALUE)
        .setClusterPort(0))
      final CompletableFuture<Void> future = new CompletableFuture<>()
      server.deployVerticle(verticle().name,
        new DeploymentOptions()
        .setConfig(new JsonObject().put(IastVertx39TestVerticle.CONFIG_HTTP_SERVER_PORT, port))
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
      return new URI("http://localhost:$port$routerBasePath")
    }
  }

  @Override
  HttpServer server() {
    return new VertxServer(routerBasePath())
  }

  protected Class<AbstractVerticle> verticle() {
    IastVertx39TestVerticle
  }

  String routerBasePath() {
    return "/"
  }
}
