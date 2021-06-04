package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.api.Function;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class RouteHandlerDecorator {
  public static final RouteHandlerDecorator ROUTE_HANDLER_DECORATOR = new RouteHandlerDecorator();

  private static final Function<Pair<CharSequence, CharSequence>, CharSequence>
      RESOURCE_NAME_JOINER =
          new Function<Pair<CharSequence, CharSequence>, CharSequence>() {
            @Override
            public CharSequence apply(Pair<CharSequence, CharSequence> input) {
              if (input.getLeft() == null) {
                return input.getRight();
              }
              return UTF8BytesString.create(
                  input.getLeft().toString().toUpperCase() + " " + input.getRight());
            }
          };

  private static final DDCache<Pair<CharSequence, CharSequence>, CharSequence> RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(64);

  public final AgentSpan withRoute(
      final AgentSpan span, final CharSequence method, final CharSequence route) {
    span.setTag(Tags.HTTP_ROUTE, route);
    if (Config.get().isHttpServerRouteBasedNaming()) {
      final CharSequence resourceName =
          RESOURCE_NAME_CACHE.computeIfAbsent(Pair.of(method, route), RESOURCE_NAME_JOINER);
      span.setResourceName(resourceName);
    }
    return span;
  }

  public final boolean hasRouteBasedResourceName(AgentSpan span) {
    if (Config.get().isHttpServerRouteBasedNaming()) {
      CharSequence route = (CharSequence) span.getTag(Tags.HTTP_ROUTE);
      return route != null && span.getResourceName().toString().endsWith(route.toString());
    }
    return false;
  }
}
