package datadog.trace.bootstrap.instrumentation.decorator.http;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.KnownTags;
import datadog.trace.api.Pair;
import datadog.trace.api.normalize.HttpResourceNames;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.OtelHttpSemantics;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class HttpResourceDecorator {
  public static final HttpResourceDecorator HTTP_RESOURCE_DECORATOR = new HttpResourceDecorator();

  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");
  private static final boolean OTEL_SEMANTICS = Config.get().isTraceOtelSemanticsEnabled();

  private final boolean shouldSetUrlResourceName =
      Config.get().isRuleEnabled("URLAsResourceNameRule");
  private final boolean hasClientPathMappings =
      !Config.get().getHttpClientPathResourceNameMapping().isEmpty();

  private HttpResourceDecorator() {}

  public final void withClientPath(AgentSpan span, CharSequence method, CharSequence path) {
    if (OTEL_SEMANTICS
        && span.getSpanType() != null
        && DDSpanTypes.HTTP_CLIENT.contentEquals(span.getSpanType())) {
      if (hasClientPathMappings && path != null) {
        Pair<CharSequence, Byte> resourceName =
            HttpResourceNames.computeForClient(
                OtelHttpSemantics.spanNameMethod(method), path, false);
        if (resourceName.getRight() == ResourceNamePriorities.HTTP_CLIENT_CONFIG_PATTERN_MATCH) {
          span.setResourceName(resourceName.getLeft(), resourceName.getRight());
          return;
        }
      }
      OtelHttpSemantics.setMethodResourceName(span, method);
    } else {
      HttpResourceNames.setForClient(span, method, path, false);
    }
  }

  public final void withServerPath(
      AgentSpan span, CharSequence method, CharSequence path, boolean encoded) {
    if (OTEL_SEMANTICS) {
      OtelHttpSemantics.setMethodResourceName(span, method);
      return;
    }
    if (!shouldSetUrlResourceName) {
      span.setResourceName(DEFAULT_RESOURCE_NAME);
      return;
    }

    HttpResourceNames.setForServer(span, method, path, encoded);
  }

  public final void withRoute(
      final AgentSpan span, final CharSequence method, final CharSequence route) {
    withRoute(span, method, route, false);
  }

  public final void withRoute(
      final AgentSpan span, final CharSequence method, final CharSequence route, boolean encoded) {
    CharSequence routeTag = route;
    if (encoded) {
      routeTag = URIUtils.decode(route.toString());
    }
    span.setTag(KnownTags.HTTP_ROUTE_ID, routeTag);
    if (OTEL_SEMANTICS) {
      final CharSequence resourceName =
          HttpResourceNames.join(OtelHttpSemantics.spanNameMethod(method), routeTag);
      span.setResourceName(resourceName, ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE);
    } else if (Config.get().isHttpServerRouteBasedNaming()) {
      final CharSequence resourceName = HttpResourceNames.join(method, route);
      span.setResourceName(resourceName, ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE);
    }
  }
}
