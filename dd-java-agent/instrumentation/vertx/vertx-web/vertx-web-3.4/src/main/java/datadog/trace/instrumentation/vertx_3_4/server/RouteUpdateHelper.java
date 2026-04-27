package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxDecorator.INSTRUMENTATION_NAME;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.vertx.ext.web.RoutingContext;

public final class RouteUpdateHelper {
  public static final String PARENT_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".parent";
  public static final String HANDLER_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".handler";
  public static final String ROUTE_CONTEXT_KEY = "dd." + Tags.HTTP_ROUTE;

  private RouteUpdateHelper() {}

  public static void updateRoute(
      final RoutingContext routingContext,
      final String method,
      final String path,
      final AgentSpan parentSpan,
      final AgentSpan handlerSpan) {
    if (method == null || path == null) {
      return;
    }
    if (!shouldUpdateRoute(routingContext, parentSpan, handlerSpan, path)) {
      return;
    }

    routingContext.put(ROUTE_CONTEXT_KEY, path);
    if (parentSpan != null) {
      HTTP_RESOURCE_DECORATOR.withRoute(parentSpan, method, path, true);
    }
    if (isVertxRouteHandlerSpan(handlerSpan)
        && handlerSpan.getResourceNamePriority() < ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE) {
      HTTP_RESOURCE_DECORATOR.withRoute(handlerSpan, method, path, true);
    }
  }

  public static void updateRouteFromContext(
      final RoutingContext routingContext,
      final AgentSpan parentSpan,
      final AgentSpan handlerSpan) {
    if (parentSpan == null && handlerSpan == null) {
      return;
    }
    if (routingContext.currentRoute() == null) {
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
    updateRoute(routingContext, method, path, parentSpan, handlerSpan);
  }

  private static boolean shouldUpdateRoute(
      final RoutingContext routingContext,
      final AgentSpan parentSpan,
      final AgentSpan handlerSpan,
      final String path) {
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

  public static boolean hasHttpRoute(final AgentSpan span) {
    return span != null && span.getTag(Tags.HTTP_ROUTE) != null;
  }

  private static boolean isVertxRouteHandlerSpan(final AgentSpan span) {
    if (span == null) {
      return false;
    }
    final CharSequence spanName = span.getSpanName();
    return spanName != null && INSTRUMENTATION_NAME.toString().contentEquals(spanName);
  }
}
