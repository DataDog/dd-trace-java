package datadog.vertx_3_9;

import datadog.smoketest.vertx_3_9.IastHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.Arrays;

public class IastVerticle extends AbstractVerticle {

  public static void main(String[] args) {
    final VertxOptions options =
        new VertxOptions()
            .setEventLoopPoolSize(1)
            .setWorkerPoolSize(2)
            .setInternalBlockingPoolSize(1);
    final Vertx vertx = Vertx.vertx(options);
    vertx.deployVerticle(new IastVerticle());
  }

  @Override
  public void start(final Promise<Void> startPromise) {
    final Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());
    Arrays.stream(IastHandler.values())
        .forEach(
            handler -> {
              handler.init(vertx);
              router.route(handler.path).handler(handler);
            });
    vertx
        .createHttpServer(new HttpServerOptions().setHandle100ContinueAutomatically(true))
        .requestHandler(router)
        .listen(
            Integer.getInteger("vertx.http.port", 8080),
            http -> {
              if (http.succeeded()) {
                startPromise.complete();
                System.out.println("HTTP server started");
              } else {
                startPromise.fail(http.cause());
              }
            });
  }
}
