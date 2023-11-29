package server;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.impl.CookieImpl;

public class IastSourceVerticle extends AbstractVerticle {

  public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";

  @Override
  public void start(final Future<Void> startPromise) {
    final int port = config().getInteger(CONFIG_HTTP_SERVER_PORT);
    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());
    router.route().handler(CookieHandler.create());
    router
        .route("/iast/propagation/cookies")
        .handler(
            rc -> {
              rc.cookies();
              rc.response().end();
            });
    router
        .route("/iast/propagation/getcookie")
        .handler(
            rc -> {
              rc.getCookie("cookie");
              rc.response().end();
            });
    router
        .route("/iast/propagation/getcookiename")
        .handler(
            rc -> {
              Cookie cookie = new CookieImpl("cookieName", "cookieValue");
              cookie.getName();
              rc.response().end();
            });
    router
        .route("/iast/propagation/getcookievalue")
        .handler(
            rc -> {
              Cookie cookie = new CookieImpl("cookieName", "cookieValue");
              cookie.getValue();
              rc.response().end();
            });
    router
        .route("/iast/propagation/headers")
        .handler(
            rc -> {
              rc.request().headers();
              rc.response().end();
            });
    router
        .route("/iast/propagation/params")
        .handler(
            rc -> {
              rc.request().params();
              rc.response().end();
            });
    router
        .route("/iast/propagation/formAttributes")
        .handler(rc -> rc.response().end(rc.request().formAttributes().get("formAttribute")));
    router
        .route("/iast/propagation/handleData")
        .handler(rc -> rc.response().end(rc.getBodyAsString()));

    vertx
        .createHttpServer(new HttpServerOptions().setHandle100ContinueAutomatically(true))
        .requestHandler(router::accept)
        .listen(
            port,
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
