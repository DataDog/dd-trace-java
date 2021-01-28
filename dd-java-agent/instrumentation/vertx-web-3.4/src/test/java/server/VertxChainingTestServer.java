package server;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN;
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import lombok.SneakyThrows;

public class VertxChainingTestServer extends AbstractVerticle {
  public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";

  @Override
  public void start(final Future<Void> startFuture) {
    final int port = config().getInteger(CONFIG_HTTP_SERVER_PORT);
    final Router router = Router.router(vertx);

    router.route().handler(VertxChainingTestServer::firstHandler);

    router
        .route(SUCCESS.getPath())
        .handler(
            ctx ->
                controller(
                    SUCCESS,
                    () ->
                        ctx.response().setStatusCode(SUCCESS.getStatus()).end(SUCCESS.getBody())));
    router
        .route(QUERY_PARAM.getPath())
        .handler(
            ctx ->
                controller(
                    QUERY_PARAM,
                    () ->
                        ctx.response()
                            .setStatusCode(QUERY_PARAM.getStatus())
                            .end(ctx.request().query())));
    router
        .route("/path/:id/param")
        .handler(
            ctx ->
                controller(
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
                    ERROR,
                    () -> ctx.response().setStatusCode(ERROR.getStatus()).end(ERROR.getBody())));
    router
        .route(EXCEPTION.getPath())
        .handler(ctx -> controller(EXCEPTION, VertxChainingTestServer::exception));

    vertx
        .createHttpServer()
        .requestHandler(router::accept)
        .listen(port, event -> startFuture.complete());
  }

  @SneakyThrows
  private static void exception() {
    throw new Exception(EXCEPTION.getBody());
  }

  private static void controller(final ServerEndpoint endpoint, final Runnable runnable) {
    assert activeSpan() != null : "Controller should have a parent span.";
    assert activeScope().isAsyncPropagating() : "Scope should be propagating async.";
    if (endpoint == NOT_FOUND || endpoint == UNKNOWN) {
      runnable.run();
      return;
    }
    runnableUnderTrace("controller", runnable);
  }

  private static void firstHandler(final RoutingContext ctx) {
    AgentTracer.activeSpan().setTag("chain", true);
    ctx.next();
  }
}
