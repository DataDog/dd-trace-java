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
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.RouteImpl;
import io.vertx.ext.web.impl.RouterImpl;

public class RouteHandlerWrapper implements Handler<RoutingContext> {
  static final String PARENT_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".parent";
  static final String HANDLER_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".handler";
  static final String ROUTE_CONTEXT_KEY = "dd." + Tags.HTTP_ROUTE;
  static final String ROUTE_OVERWRITE_DEBUG_TAG = "dd.debug.vertx.route_overwrite";

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

        span = startSpan(INSTRUMENTATION_NAME);
        routingContext.put(HANDLER_SPAN_CONTEXT_KEY, span);

        routingContext.response().endHandler(new EndHandlerWrapper(routingContext));
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

  private void setRoute(RoutingContext routingContext) {
    final AgentSpan parentSpan = routingContext.get(PARENT_SPAN_CONTEXT_KEY);
    final AgentSpan handlerSpan = routingContext.get(HANDLER_SPAN_CONTEXT_KEY);
    if (parentSpan == null && handlerSpan == null) {
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
    updateRoute(routingContext, method, path, parentSpan, handlerSpan, "route_handler");
  }

  static void updateRoute(
      final RoutingContext routingContext,
      final String method,
      final String path,
      final AgentSpan parentSpan,
      final AgentSpan handlerSpan,
      final String source) {
    if (method == null || path == null) {
      return;
    }
    final String previousRoute = routingContext.get(ROUTE_CONTEXT_KEY);
    if (!shouldUpdateRoute(routingContext, parentSpan, handlerSpan, path)) {
      return;
    }

    routingContext.put(ROUTE_CONTEXT_KEY, path);
    final String previous = previousRoute == null ? "" : previousRoute;
    final String debugValue = source + ":" + previous + "->" + path;
    if (parentSpan != null) {
      HTTP_RESOURCE_DECORATOR.withRoute(parentSpan, method, path, true);
      parentSpan.setTag(ROUTE_OVERWRITE_DEBUG_TAG, debugValue);
    }
    if (handlerSpan != null
        && handlerSpan.getResourceNamePriority() < ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE) {
      HTTP_RESOURCE_DECORATOR.withRoute(handlerSpan, method, path, true);
      handlerSpan.setTag(ROUTE_OVERWRITE_DEBUG_TAG, debugValue);
    }
  }

  static boolean shouldUpdateRoute(
      final RoutingContext routingContext,
      final AgentSpan parentSpan,
      final AgentSpan handlerSpan,
      final String path) {
    if (parentSpan == null && handlerSpan == null) {
      return false;
    }
    final String currentRoute = routingContext.get(ROUTE_CONTEXT_KEY);
    if (currentRoute != null && currentRoute.equals(path)) {
      return false;
    }
    // do not override route with a "/" if it's already set (it's probably more meaningful)
    if (!path.equals("/")) {
      return true;
    }
    return !hasHttpRoute(parentSpan) && !hasHttpRoute(handlerSpan);
  }

  private static boolean hasHttpRoute(final AgentSpan span) {
    return span != null && span.getTag(Tags.HTTP_ROUTE) != null;
  }
}
