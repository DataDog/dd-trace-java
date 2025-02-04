package server

import datadog.trace.agent.test.base.HttpServer
import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.ThreadingModel
import io.vertx.core.Vertx
import io.vertx.core.internal.VertxInternal
import io.vertx.core.json.JsonObject

import java.util.concurrent.CompletableFuture

class VertxServer implements HttpServer {
  private VertxInternal server
  private String routerBasePath
  private port
  private boolean useWorker
  Class<AbstractVerticle> verticle

  VertxServer(Class<AbstractVerticle> verticle, String routerBasePath, boolean useWorker = false) {
    this.routerBasePath = routerBasePath
    this.verticle = verticle
    this.useWorker = useWorker
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

    def deployOptions = new DeploymentOptions()
      .setConfig(new JsonObject().put(VertxTestServer.CONFIG_HTTP_SERVER_PORT, 0))
      .setInstances(1)

    if (useWorker) {
      deployOptions = deployOptions.setWorkerPoolSize(1).setThreadingModel(ThreadingModel.WORKER)
    }
    server.deployVerticle(verticle.name, deployOptions).await()

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
