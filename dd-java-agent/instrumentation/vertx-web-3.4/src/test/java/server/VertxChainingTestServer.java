package server;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

public class VertxChainingTestServer extends VertxTestServer {
  @Override
  protected void customizeBeforeRoutes(Router router) {
    router.route().handler(VertxChainingTestServer::firstHandler);
  }

  private static void firstHandler(final RoutingContext ctx) {
    AgentTracer.activeSpan().setTag("chain", true);
    ctx.next();
  }
}
