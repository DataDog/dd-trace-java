package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.vertx.ext.web.RoutingContext;

public final class RouteUpdateHelper {
  public static final String PARENT_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".parent";
  public static final String HANDLER_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".handler";
  public static final String ROUTE_CONTEXT_KEY = "dd." + Tags.HTTP_ROUTE;
  public static final String ROUTE_OVERWRITE_DEBUG_TAG = "dd.debug.vertx.route_overwrite";

  private RouteUpdateHelper() {}

  public static void updateRoute(
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

  public static boolean shouldUpdateRoute(
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

  public static boolean hasHttpRoute(final AgentSpan span) {
    return span != null && span.getTag(Tags.HTTP_ROUTE) != null;
  }
}
