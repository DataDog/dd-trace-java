package datadog.trace.bootstrap.instrumentation.decorator.http;

import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.http.HttpResourceNames;
import datadog.trace.api.normalize.HttpPathNormalizer;
import datadog.trace.api.normalize.HttpPathNormalizers;
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

  private final HttpPathNormalizer simplePathNormalizer;

  private HttpResourceDecorator() {
    simplePathNormalizer = HttpPathNormalizers.simple();
  }

  public final AgentSpan withClientPath(AgentSpan span, CharSequence method, CharSequence path) {
    span.setResourceName(
        HttpResourceNames.compute(method, simplePathNormalizer.normalize(path.toString())),
        ResourceNamePriorities.HTTP_PATH_NORMALIZER);
    return span;
  }

  public final AgentSpan withServerPath(
      AgentSpan span, CharSequence method, CharSequence path, boolean encoded) {
    if (!shouldSetUrlResourceName) {
      return span.setResourceName(DEFAULT_RESOURCE_NAME);
    }
    Pair<String, Byte> normalized = HttpPathNormalizers.chainWithPriority(path.toString(), encoded);
    span.setResourceName(
        HttpResourceNames.compute(method, normalized.getLeft()), normalized.getRight());
    return span;
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
      final CharSequence resourceName = HttpResourceNames.compute(method, route);
      span.setResourceName(resourceName, ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE);
    }
    return span;
  }
}
