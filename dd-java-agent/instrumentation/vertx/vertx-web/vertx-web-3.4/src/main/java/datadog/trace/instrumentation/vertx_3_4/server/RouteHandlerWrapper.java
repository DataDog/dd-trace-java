package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxDecorator.DECORATE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxDecorator.INSTRUMENTATION_NAME;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.RouteImpl;
import io.vertx.ext.web.impl.RouterImpl;

public class RouteHandlerWrapper implements Handler<RoutingContext> {
  static final String PARENT_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".parent";
  static final String HANDLER_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".handler";
  static final String ROUTE_CONTEXT_KEY = "dd." + Tags.HTTP_ROUTE;

  private final Handler<RoutingContext> actual;
  private final boolean spanStarter;

  public RouteHandlerWrapper(final Handler<RoutingContext> handler) {
    actual = handler;
    // When mounting a sub router, the handler is a lambda in either RouterImpl or RouteImpl, so
    // this skips that. This prevents routers from creating a span during handling. In the event
    // a route is not found, without this code, a span would be created for the router when it
    // shouldn't
    String name = handler.getClass().getName();
    spanStarter =
        !(name.startsWith(RouterImpl.class.getName())
            || name.startsWith(RouteImpl.class.getName()));
  }

  @Override
  public void handle(final RoutingContext routingContext) {
    AgentSpan span = routingContext.get(HANDLER_SPAN_CONTEXT_KEY);
    if (spanStarter) {
      if (span == null) {
        AgentSpan parentSpan = activeSpan();
        routingContext.put(PARENT_SPAN_CONTEXT_KEY, parentSpan);

        span = startSpan("vertx", INSTRUMENTATION_NAME);
        routingContext.put(HANDLER_SPAN_CONTEXT_KEY, span);

        routingContext.response().endHandler(new EndHandlerWrapper(routingContext));
        // Fallback finish path. The response.endHandler we register above can be
        // silently skipped on Vert.x 3.x in two situations:
        //   1. sendFile() — only bodyEndHandler is invoked on this path.
        //   2. Synthetic transports (e.g. an in-memory Netty channel) on 3.9,
        //      where HttpServerResponseImpl.end gates endHandler behind `!closed`
        //      and the response is closed synchronously by responseComplete().
        // RoutingContext.addBodyEndHandler is wired to response.bodyEndHandler,
        // which HttpServerResponseImpl invokes on every response-end path across
        // the 3.x range. RoutingContext.addEndHandler does not exist until 4.0.
        routingContext.addBodyEndHandler(v -> finishHandlerSpan(routingContext));
        DECORATE.afterStart(span);
        span.setResourceName(DECORATE.className(actual.getClass()));
      }
      setRoute(routingContext);
    }
    try (final AgentScope scope = span != null ? activateSpan(span) : noopScope()) {
      try {
        actual.handle(routingContext);
      } catch (final Throwable t) {
        DECORATE.onError(span, t);
        throw t;
      }
    }
  }

  // Idempotently finish the route-handler span. Both EndHandlerWrapper (the
  // response.endHandler path) and the routingContext.addBodyEndHandler fallback
  // may call this; the first one to win clears HANDLER_SPAN_CONTEXT_KEY so the
  // second is a no-op.
  static void finishHandlerSpan(final RoutingContext routingContext) {
    final AgentSpan span = routingContext.get(HANDLER_SPAN_CONTEXT_KEY);
    if (span == null) {
      return;
    }
    routingContext.put(HANDLER_SPAN_CONTEXT_KEY, null);
    DECORATE.onResponse(span, routingContext.response());
    span.finish();
  }

  private void setRoute(RoutingContext routingContext) {
    final AgentSpan parentSpan = routingContext.get(PARENT_SPAN_CONTEXT_KEY);
    if (parentSpan == null) {
      return;
    }

    final String method = routingContext.request().rawMethod();
    String mountPoint = routingContext.mountPoint();
    String path = routingContext.currentRoute().getPath();
    if (mountPoint != null && !mountPoint.isEmpty()) {
      if (mountPoint.charAt(mountPoint.length() - 1) == '/'
          && path != null
          && !path.isEmpty()
          && path.charAt(0) == '/') {
        mountPoint = mountPoint.substring(0, mountPoint.length() - 1);
      }
      path = mountPoint + path;
    }
    if (method != null && path != null && shouldUpdateRoute(routingContext, parentSpan, path)) {
      routingContext.put(ROUTE_CONTEXT_KEY, path);
      HTTP_RESOURCE_DECORATOR.withRoute(parentSpan, method, path, true);
    }
  }

  static boolean shouldUpdateRoute(
      final RoutingContext routingContext, final AgentSpan span, final String path) {
    if (span == null) {
      return false;
    }
    final String currentRoute = routingContext.get(ROUTE_CONTEXT_KEY);
    if (currentRoute != null && currentRoute.equals(path)) {
      return false;
    }
    // do not override route with a "/" if it's already set (it's probably more meaningful)
    return !path.equals("/") || span.getTag(Tags.HTTP_ROUTE) == null;
  }
}
