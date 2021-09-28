package datadog.trace.bootstrap.instrumentation.decorator.http;

import static datadog.trace.bootstrap.instrumentation.decorator.http.UrlBasedResourceNameCalculator.RESOURCE_NAME_CALCULATOR;
import static datadog.trace.bootstrap.instrumentation.decorator.http.UrlBasedResourceNameCalculator.SIMPLE_PATH_NORMALIZER;

import datadog.trace.api.Config;
import datadog.trace.api.Function;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class HttpResourceDecorator {
  public static final HttpResourceDecorator HTTP_RESOURCE_DECORATOR = new HttpResourceDecorator();

  static final Function<Pair<CharSequence, CharSequence>, UTF8BytesString> RESOURCE_NAME_JOINER =
      new Function<Pair<CharSequence, CharSequence>, UTF8BytesString>() {
        @Override
        public UTF8BytesString apply(Pair<CharSequence, CharSequence> input) {
          if (input.getLeft() == null) {
            return UTF8BytesString.create(input.getRight());
          }
          return UTF8BytesString.create(
              input.getLeft().toString().toUpperCase() + " " + input.getRight());
        }
      };

  private static final DDCache<Pair<CharSequence, CharSequence>, CharSequence> RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(64);

  /**
   * For use in places where we only want to use the simple path normalizer, otherwise use
   * withPath()
   */
  public final AgentSpan withSimplePath(AgentSpan span, CharSequence method, CharSequence path) {
    span.setResourceName(
        RESOURCE_NAME_CACHE.computeIfAbsent(
            Pair.of(method, (CharSequence) SIMPLE_PATH_NORMALIZER.normalize(path.toString())),
            RESOURCE_NAME_JOINER),
        ResourceNamePriorities.HTTP_PATH_NORMALIZER);
    return span;
  }

  public final AgentSpan withPath(
      AgentSpan span, CharSequence method, CharSequence path, boolean encoded) {
    span.setResourceName(
        RESOURCE_NAME_CALCULATOR.calculate(method.toString(), path.toString(), encoded));
    return span;
  }

  public final AgentSpan withRoute(
      final AgentSpan span, final CharSequence method, final CharSequence route) {
    span.setTag(Tags.HTTP_ROUTE, route);
    if (Config.get().isHttpServerRouteBasedNaming()) {
      final CharSequence resourceName =
          RESOURCE_NAME_CACHE.computeIfAbsent(Pair.of(method, route), RESOURCE_NAME_JOINER);
      span.setResourceName(resourceName, ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE);
    }
    return span;
  }
}
