package datadog.trace.bootstrap.instrumentation.decorator.http;

import datadog.trace.api.Config;
import datadog.trace.api.normalize.HttpResourceNames;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class HttpResourceDecorator {
  public static final HttpResourceDecorator HTTP_RESOURCE_DECORATOR = new HttpResourceDecorator();

  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");

  private final boolean shouldSetUrlResourceName =
      Config.get().isRuleEnabled("URLAsResourceNameRule");

  private HttpResourceDecorator() {}

  public final AgentSpan withClientPath(AgentSpan span, CharSequence method, CharSequence path) {
    return HttpResourceNames.setForClient(span, method, path, false);
  }

  public final AgentSpan withServerPath(
      AgentSpan span, CharSequence method, CharSequence path, boolean encoded) {
    if (!shouldSetUrlResourceName) {
      return span.setResourceName(DEFAULT_RESOURCE_NAME);
    }

    return HttpResourceNames.setForServer(span, method, path, encoded);
  }

  public final AgentSpan withRoute(
      final AgentSpan span, final CharSequence method, final CharSequence route) {
    return withRoute(span, method, route, false);
  }

  public final AgentSpan withRoute(
      final AgentSpan span, final CharSequence method, final CharSequence route, boolean encoded) {
    CharSequence routeTag = route;
    if (encoded) {
      routeTag = URIUtils.decode(route.toString());
    }
    span.setTag(Tags.HTTP_ROUTE, routeTag);
    if (Config.get().isHttpServerRouteBasedNaming()) {
      final CharSequence resourceName = HttpResourceNames.join(method, route);
      span.setResourceName(resourceName, ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE);
    }
    return span;
  }
}
