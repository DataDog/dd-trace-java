package datadog.trace.bootstrap.instrumentation.decorator.http;

import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.function.Function;

public class HttpResourceDecorator {
  public static final HttpResourceDecorator HTTP_RESOURCE_DECORATOR = new HttpResourceDecorator();

  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");

  private static final Function<Pair<CharSequence, CharSequence>, UTF8BytesString>
      RESOURCE_NAME_JOINER =
          input -> {
            if (input.getLeft() == null) {
              return UTF8BytesString.create(input.getRight());
            }
            return UTF8BytesString.create(
                input.getLeft().toString().toUpperCase() + " " + input.getRight());
          };

  private static final DDCache<Pair<CharSequence, CharSequence>, CharSequence> RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(64);

  private final boolean shouldSetUrlResourceName =
      Config.get().isRuleEnabled("URLAsResourceNameRule");

  private final AntPatternPathNormalizer antPatternPathNormalizer;
  private final SimplePathNormalizer simplePathNormalizer;

  private HttpResourceDecorator() {
    antPatternPathNormalizer =
        new AntPatternPathNormalizer(Config.get().getHttpServerPathResourceNameMapping());
    simplePathNormalizer = new SimplePathNormalizer();
  }

  /**
   * For use in places where we only want to use the simple path normalizer, otherwise use
   * withPath()
   */
  public final AgentSpan withClientPath(AgentSpan span, CharSequence method, CharSequence path) {
    span.setResourceName(
        RESOURCE_NAME_CACHE.computeIfAbsent(
            Pair.of(method, (CharSequence) simplePathNormalizer.normalize(path.toString())),
            RESOURCE_NAME_JOINER),
        ResourceNamePriorities.HTTP_PATH_NORMALIZER);
    return span;
  }

  public final AgentSpan withServerPath(
      AgentSpan span, CharSequence method, CharSequence path, boolean encoded) {
    if (!shouldSetUrlResourceName) {
      return span.setResourceName(DEFAULT_RESOURCE_NAME);
    }
    byte priority;

    String resourcePath = antPatternPathNormalizer.normalize(path.toString(), encoded);
    if (resourcePath != null) {
      priority = ResourceNamePriorities.HTTP_SERVER_CONFIG_PATTERN_MATCH;
    } else {
      resourcePath = simplePathNormalizer.normalize(path.toString(), encoded);
      priority = ResourceNamePriorities.HTTP_PATH_NORMALIZER;
    }
    span.setResourceName(
        RESOURCE_NAME_CACHE.computeIfAbsent(
            Pair.of(method, (CharSequence) resourcePath), RESOURCE_NAME_JOINER),
        priority);
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
      final CharSequence resourceName =
          RESOURCE_NAME_CACHE.computeIfAbsent(Pair.of(method, route), RESOURCE_NAME_JOINER);
      span.setResourceName(resourceName, ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE);
    }
    return span;
  }
}
