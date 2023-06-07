package server;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK;
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTraceAsync;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.appsec.api.blocking.Blocking;
import datadog.trace.agent.test.base.HttpServerTest;
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

public class IastVertx39TestVerticle extends AbstractVerticle {
  public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";

  @Override
  public void start(final Future<Void> startFuture) {
    final int port = config().getInteger(CONFIG_HTTP_SERVER_PORT);
    Router router = Router.router(vertx);

    customizeBeforeRoutes(router);
    router
        .route("/vulnerability/setHeaderString")
        .handler(
            ctx ->
                controller(
                    ctx,
                    SUCCESS,
                    () -> {
                      final String headerName = ctx.request().getParam("name");
                      final String headerValue = ctx.request().getParam("value");
                      ctx.response()
                          .setStatusCode(SUCCESS.getStatus())
                          .putHeader(headerName, headerValue)
                          .end(SUCCESS.getBody());
                    }));
    router
        .route("/iast/vulnerabilities/insecureCookie")
        .handler(
            ctx ->
                controller(
                    ctx,
                    SUCCESS,
                    () -> {
                      final String headerName = ctx.request().getParam("name");
                      final String headerValue = ctx.request().getParam("value");
                      Cookie cookie = Cookie.cookie(headerName, headerValue);
                      if ("true".equals(ctx.request().getParam("secure"))) {
                        cookie.setSecure(true);
                      }
                      ctx.response()
                          .setStatusCode(SUCCESS.getStatus())
                          .addCookie(cookie)
                          .end("Cookie Set");
                    }));
    router
        .route(SUCCESS.getPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    SUCCESS,
                    () ->
                        ctx.response().setStatusCode(SUCCESS.getStatus()).end(SUCCESS.getBody())));
    router
        .route(FORWARDED.getPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    FORWARDED,
                    () ->
                        ctx.response()
                            .setStatusCode(FORWARDED.getStatus())
                            .end(ctx.request().getHeader("x-forwarded-for"))));
    router
        .route(CREATED.getPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    CREATED,
                    () ->
                        ctx.request()
                            .bodyHandler(
                                body ->
                                    ctx.response()
                                        .setStatusCode(CREATED.getStatus())
                                        .end(CREATED.getBody() + ": " + body.toString()))));
    router.route(BODY_URLENCODED.getPath()).handler(BodyHandler.create());
    router
        .route(BODY_URLENCODED.getPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    BODY_URLENCODED,
                    () -> {
                      String res = "[";
                      MultiMap entries = ctx.request().formAttributes();
                      for (String name : entries.names()) {
                        if (name.equals("ignore")) {
                          continue;
                        }
                        if (res.length() > 1) {
                          res += ", ";
                        }
                        res += name;
                        res += ":[";
                        int i = 0;
                        for (String s : entries.getAll(name)) {
                          if (i++ > 0) {
                            res += ", ";
                          }
                          res += s;
                        }
                        res += ']';
                      }
                      res += ']';
                      ctx.response().setStatusCode(BODY_URLENCODED.getStatus()).end(res);
                    }));
    router.route(BODY_JSON.getPath()).handler(BodyHandler.create());
    router
        .route(BODY_JSON.getPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    BODY_JSON,
                    () -> {
                      JsonObject json = ctx.getBodyAsJson();
                      ctx.response().setStatusCode(BODY_JSON.getStatus()).end(json.toString());
                    }));
    router
        .route(QUERY_ENCODED_BOTH.getRawPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    QUERY_ENCODED_BOTH,
                    () ->
                        ctx.response()
                            .setStatusCode(QUERY_ENCODED_BOTH.getStatus())
                            .end(QUERY_ENCODED_BOTH.bodyForQuery(ctx.request().query()))));
    router
        .route(QUERY_ENCODED_QUERY.getPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    QUERY_ENCODED_QUERY,
                    () ->
                        ctx.response()
                            .setStatusCode(QUERY_ENCODED_QUERY.getStatus())
                            .end(QUERY_ENCODED_QUERY.bodyForQuery(ctx.request().query()))));
    router
        .route(QUERY_PARAM.getPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    QUERY_PARAM,
                    () ->
                        ctx.response()
                            .setStatusCode(QUERY_PARAM.getStatus())
                            .end(ctx.request().query())));
    router
        .route(USER_BLOCK.getPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    USER_BLOCK,
                    () -> {
                      Blocking.forUser("user-to-block").blockIfMatch();
                      ctx.response().end("Should not be reached");
                    }));
    router
        .route("/path/:id/param")
        .handler(
            ctx ->
                controller(
                    ctx,
                    PATH_PARAM,
                    () ->
                        ctx.response()
                            .setStatusCode(PATH_PARAM.getStatus())
                            .end(ctx.request().getParam("id"))));
    router
        .route(REDIRECT.getPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    REDIRECT,
                    () ->
                        ctx.response()
                            .setStatusCode(REDIRECT.getStatus())
                            .putHeader("location", REDIRECT.getBody())
                            .end()));
    router
        .route(ERROR.getPath())
        .handler(
            ctx ->
                controller(
                    ctx,
                    ERROR,
                    () -> ctx.response().setStatusCode(ERROR.getStatus()).end(ERROR.getBody())));
    router
        .route(EXCEPTION.getPath())
        .handler(ctx -> controller(ctx, EXCEPTION, IastVertx39TestVerticle::exception));

    router = customizeAfterRoutes(router);

    vertx
        .createHttpServer()
        .requestHandler(router::accept)
        .listen(port, event -> startFuture.complete());
  }

  protected void customizeBeforeRoutes(Router router) {}

  protected Router customizeAfterRoutes(final Router router) {
    return router;
  }

  private static void exception() {
    throw new RuntimeException(EXCEPTION.getBody());
  }

  private static void controller(
      RoutingContext ctx, final ServerEndpoint endpoint, final Runnable runnable) {
    assert activeSpan() != null : "Controller should have a parent span.";
    assert activeScope().isAsyncPropagating() : "Scope should be propagating async.";
    ctx.response()
        .putHeader(
            HttpServerTest.getIG_RESPONSE_HEADER(), HttpServerTest.getIG_RESPONSE_HEADER_VALUE());
    if (endpoint == NOT_FOUND || endpoint == UNKNOWN) {
      runnable.run();
      return;
    }
    runnableUnderTraceAsync("controller", runnable);
  }
}
