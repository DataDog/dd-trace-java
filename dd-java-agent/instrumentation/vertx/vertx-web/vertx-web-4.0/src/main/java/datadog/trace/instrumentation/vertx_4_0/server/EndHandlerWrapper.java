package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.instrumentation.vertx_4_0.server.RouteUpdateHelper.HANDLER_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_4_0.server.RouteUpdateHelper.PARENT_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_4_0.server.RouteUpdateHelper.updateRoute;
import static datadog.trace.instrumentation.vertx_4_0.server.VertxDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public class EndHandlerWrapper implements Handler<Void> {
  private final RoutingContext routingContext;

  public Handler<Void> actual;

  EndHandlerWrapper(RoutingContext routingContext) {
    this.routingContext = routingContext;
  }

  @Override
  public void handle(final Void event) {
    AgentSpan span = routingContext.get(HANDLER_SPAN_CONTEXT_KEY);
    AgentSpan parentSpan = routingContext.get(PARENT_SPAN_CONTEXT_KEY);
    try {
      if (actual != null) {
        actual.handle(event);
      }
    } finally {
      if (routingContext.currentRoute() != null) {
        final String method = routingContext.request().method().name();
        String path = routingContext.currentRoute().getPath();
        if (path == null) {
          path = routingContext.currentRoute().getName();
        }
        final String mountPoint = routingContext.mountPoint();
        if (mountPoint != null && path != null) {
          final String noBackslashhMountPoint =
              mountPoint.endsWith("/")
                  ? mountPoint.substring(0, mountPoint.lastIndexOf("/"))
                  : mountPoint;
          path = noBackslashhMountPoint + path;
        }
        updateRoute(routingContext, method, path, parentSpan, span, "end_handler");
      }
      if (span != null) {
        DECORATE.onResponse(span, routingContext.response());
        span.finish();
      }
    }
  }
}
