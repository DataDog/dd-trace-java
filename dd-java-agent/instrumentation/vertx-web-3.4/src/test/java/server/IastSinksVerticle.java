package server;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;

public class IastSinksVerticle extends AbstractVerticle {

  public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";

  @Override
  public void start(final Future<Void> startFuture) {
    final int port = config().getInteger(CONFIG_HTTP_SERVER_PORT);
    Router router = Router.router(vertx);
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
        .route("/iast/sinks/putheader1")
        .handler(
            rc -> {
              final String headerName = rc.request().getParam("name");
              final String headerValue = rc.request().getParam("value");
              rc.response().putHeader(headerName, headerValue).end();
            });

    vertx
        .createHttpServer()
        .requestHandler(router::accept)
        .listen(port, event -> startFuture.complete());
  }
}
