package server;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

public class VertxMiddlewareTestServer extends VertxTestServer {
  @Override
  protected void customizeBeforeRoutes(Router router) {
    router.route().handler(VertxMiddlewareTestServer::firstHandler);
  }

  private static void firstHandler(final RoutingContext ctx) {
    AgentTracer.activeSpan().setTag("before", true);
    ctx.next();
  }
}
