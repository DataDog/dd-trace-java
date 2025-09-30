package server;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

public class IastSinksVerticle extends AbstractVerticle {

  @Override
  public void start(final Promise<Void> startPromise) throws Exception {
    final Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());
    router
        .route("/iast/sinks/reroute1")
        .handler(
            rc -> {
              final String path = rc.request().getParam("path");
              rc.reroute(path);
            });
    router
        .route("/iast/sinks/reroute2")
        .handler(
            rc -> {
              final String path = rc.request().getParam("path");
              rc.reroute(HttpMethod.GET, path);
            });
    router
        .route("/iast/sinks/redirectheader")
        .handler(
            rc -> {
              final String name = rc.request().getParam("name");
              final String value = rc.request().getParam("value");
              rc.response().putHeader(name, value).end();
            });

    final EventBus eventBus = vertx.eventBus();
    final HttpServerOptions serverOptions = new HttpServerOptions();
    serverOptions.setHandle100ContinueAutomatically(true);
    vertx
        .createHttpServer(serverOptions)
        .requestHandler(router)
        .listen(0)
        .onSuccess(
            server ->
                eventBus
                    .request("PORT_DATA", server.actualPort())
                    .andThen(
                        ar -> {
                          if (ar.succeeded()) {
                            startPromise.complete();
                          } else {
                            startPromise.fail(ar.cause());
                          }
                        }))
        .onFailure(startPromise::fail);
  }
}
