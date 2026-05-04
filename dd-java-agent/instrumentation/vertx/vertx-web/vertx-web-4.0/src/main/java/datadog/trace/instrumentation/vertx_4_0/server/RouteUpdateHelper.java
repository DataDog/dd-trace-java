package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;

public final class RouteUpdateHelper {
  public static final String PARENT_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".parent";
  public static final String HANDLER_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".handler";
  public static final String ROUTE_CONTEXT_KEY = "dd." + Tags.HTTP_ROUTE;
  private static final String MATCHED_ROUTE_CONTEXT_KEY = "dd.vertx.matched_route";
  private static final String VERTX_ROUTE_HANDLER_SPAN_NAME = "vertx.route-handler";

  private RouteUpdateHelper() {}

  public static void updateRouteFromContext(
      final RoutingContext routingContext,
      final AgentSpan parentSpan,
      final AgentSpan handlerSpan) {
    final Route currentRoute = routingContext.currentRoute();
    if (currentRoute == null) {
      return;
    }
    final String contextRoute = routePath(routingContext, routePath(currentRoute));
    final String matchedRoute = routingContext.get(MATCHED_ROUTE_CONTEXT_KEY);
    updateRoute(
        routingContext,
        matchedRoute != null ? matchedRoute : contextRoute,
        parentSpan,
        handlerSpan);
  }

  public static void updateRouteFromMatchedRoute(
      final RoutingContext routingContext,
      final Object route,
      final AgentSpan parentSpan,
      final AgentSpan handlerSpan) {
    // try to get the route from the object, else, fallback to context
    if (route instanceof Route) {
      final String matchedRoute = routePath(routingContext, routePath((Route) route));
      if (isConcreteRoute(routingContext, matchedRoute)) {
        // Keep the leaf route found in matches(); later handler/finally code may only see a
        // broader currentRoute, for example a subrouter mount route.
        routingContext.put(MATCHED_ROUTE_CONTEXT_KEY, matchedRoute);
        updateRoute(routingContext, matchedRoute, parentSpan, handlerSpan);
        return;
      }
    }
    updateRouteFromContext(routingContext, parentSpan, handlerSpan);
  }

  private static void updateRoute(
      final RoutingContext routingContext,
      final String path,
      final AgentSpan parentSpan,
      final AgentSpan handlerSpan) {
    final String method = routingContext.request().method().name();
    if ((method == null || path == null)
        || (parentSpan == null && handlerSpan == null)
        || !shouldUpdateRoute(routingContext, parentSpan, handlerSpan, path)) {
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

  private static String routePath(final Route route) {
    final String path = route.getPath();
    if (path != null) {
      return path;
    }
    // getName returns the name of the route, if not path or the pattern or null
    return route.getName();
  }

  private static String routePath(final RoutingContext routingContext, final String path) {
    if (path == null) {
      return null;
    }
    final String mountPoint = routingContext.mountPoint();
    if (mountPoint == null || mountPoint.isEmpty()) {
      return path;
    }
    return trimTrailingSlash(mountPoint) + withLeadingSlash(path);
  }

  private static String trimTrailingSlash(final String path) {
    if (path.charAt(path.length() - 1) == '/') {
      return path.substring(0, path.length() - 1);
    }
    return path;
  }

  private static String withLeadingSlash(final String path) {
    if (!path.isEmpty() && path.charAt(0) == '/') {
      return path;
    }
    return "/" + path;
  }

  private static boolean isConcreteRoute(final RoutingContext routingContext, final String path) {
    if (path == null || path.indexOf('*') >= 0) {
      return false;
    }
    final String requestPath = routingContext.request().path();
    return requestPath == null
        || !path.endsWith("/")
        || path.length() >= requestPath.length()
        || !requestPath.startsWith(path);
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
    return spanName != null && VERTX_ROUTE_HANDLER_SPAN_NAME.contentEquals(spanName);
  }
}
