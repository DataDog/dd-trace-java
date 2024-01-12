package server;

import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastContext;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastSourcesVerticle extends AbstractVerticle {

  private static final String EVENT_BUS_ENDPOINT = "EVENT_BUS";

  private static final Logger LOGGER = LoggerFactory.getLogger(IastSourcesVerticle.class);

  @Override
  public void start(final Promise<Void> startPromise) throws Exception {
    final Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());
    router
        .route("/iast/sources/header")
        .handler(
            rc -> {
              final String value = rc.request().getHeader("name");
              rc.response().end("Received " + value);
            });
    router
        .route("/iast/sources/headers")
        .handler(
            rc -> {
              final MultiMap value = rc.request().headers();
              rc.response().end("Received " + value.get("name"));
            });
    router
        .route("/iast/sources/cookie")
        .handler(
            rc -> {
              final Cookie cookie = rc.getCookie("name");
              rc.response().end("Received " + cookie.getName() + " " + cookie.getValue());
            });
    router
        .route("/iast/sources/path/:name")
        .handler(
            rc -> {
              final String value = rc.pathParam("name");
              rc.response().end("Received " + value);
            });
    router
        .route("/iast/sources/parameter")
        .handler(
            rc -> {
              final String value = rc.request().getParam("name");
              rc.response().end("Received " + value);
            });
    router
        .route("/iast/sources/parameters")
        .handler(
            rc -> {
              final MultiMap value = rc.request().params();
              rc.response().end("Received " + value.get("name"));
            });
    router
        .route("/iast/sources/form")
        .handler(
            rc -> {
              final String value = rc.request().getFormAttribute("name");
              rc.response().end("Received " + value);
            });
    router
        .route("/iast/sources/body/string")
        .handler(
            rc -> {
              final String encoding = rc.request().getParam("encoding");
              if (encoding != null) {
                rc.response().end("Received " + rc.getBodyAsString(encoding));
              } else {
                rc.response().end("Received " + rc.getBodyAsString());
              }
            });
    router
        .route("/iast/sources/body/json")
        .handler(
            rc -> {
              rc.response().end("Received " + rc.getBodyAsJson());
            });
    router
        .route("/body/jsonArray")
        .handler(
            rc -> {
              rc.response().end("Received " + rc.getBodyAsJsonArray());
            });

    router
        .route("/iast/sources/eventBus")
        .handler(
            rc -> {
              final JsonObject target = rc.getBodyAsJson();
              rc.vertx()
                  .eventBus()
                  .request(
                      EVENT_BUS_ENDPOINT,
                      target,
                      reply -> {
                        if (reply.succeeded()) {
                          rc.response().end(reply.result().body().toString());
                        } else {
                          rc.fail(reply.cause());
                        }
                      });
            });
    router
        .route("/iast/vulnerabilities/insecureCookie")
        .handler(
            rc -> {
              final String cookieName = rc.request().getParam("name");
              final String cookieValue = rc.request().getParam("value");
              final String secure = rc.request().getParam("secure");
              Cookie cookie = Cookie.cookie(cookieName, cookieValue);
              if ("true".equals(secure)) {
                cookie.setSecure(true);
              }
              rc.response().addCookie(cookie).end("Cookie Set");
            });

    final EventBus eventBus = vertx.eventBus();
    eventBus.consumer(
        EVENT_BUS_ENDPOINT,
        message -> {
          final JsonObject payload = (JsonObject) message.body();
          final String name = payload.getString("name");
          try {
            final IastContext ctx = IastContext.Provider.get();
            if (ctx == null) {
              throw new IllegalStateException("No IAST context present");
            }
            final TaintedObjects to = ctx.getTaintedObjects();
            final boolean tainted = to.get(name) != null;
            message.reply(tainted ? "OK" : "NO_OK");
          } catch (Throwable e) {
            LOGGER.error("Failed to handle event bus message", e);
            message.reply("NO_OK");
          }
        });

    final HttpServerOptions serverOptions = new HttpServerOptions();
    if (config().getBoolean("https")) {
      final SelfSignedCertificate certificate = SelfSignedCertificate.create();
      serverOptions.setSsl(true);
      serverOptions.setUseAlpn(true);
      serverOptions.setTrustOptions(certificate.trustOptions());
      serverOptions.setKeyCertOptions(certificate.keyCertOptions());
    }
    serverOptions.setHandle100ContinueAutomatically(true);
    vertx
        .createHttpServer(serverOptions)
        .requestHandler(router)
        .listen(0)
        .onSuccess(
            server ->
                eventBus.request(
                    "PORT_DATA",
                    server.actualPort(),
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
