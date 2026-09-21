package datadog.trace.bootstrap.instrumentation.decorator.http;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;

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

  public final void withClientPath(AgentSpan span, CharSequence method, CharSequence path) {
    HttpResourceNames.setForClient(span, method, path, false);
  }

  public final void withServerPath(
      AgentSpan span, CharSequence method, CharSequence path, boolean encoded) {
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
    if (encoded && route != null) {
      routeTag = URIUtils.decode(route.toString());
    }
    // Framework route templates are relative to the servlet application. Include the deployment
    // context here so every servlet-hosted framework reports the same externally visible route.
    final String servletContext = servletContext(span);
    final String decodedServletContext = URIUtils.decode(servletContext);
    routeTag = prependServletContext(decodedServletContext, routeTag);
    span.setTag(Tags.HTTP_ROUTE, routeTag);
    if (Config.get().isHttpServerRouteBasedNaming()) {
      final CharSequence resourceRoute =
          encoded ? prependServletContext(servletContext, route) : routeTag;
      final CharSequence resourceName = HttpResourceNames.join(method, resourceRoute);
      span.setResourceName(resourceName, ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE);
    }
  }

  public final void withServletContext(final AgentSpan span, final String contextPath) {
    final String previousServletContext = servletContext(span);
    span.setTag(SERVLET_CONTEXT, contextPath);
    if (previousServletContext == null && servletContext(span) != null) {
      final Object route = span.getTag(Tags.HTTP_ROUTE);
      final Object method = span.getTag(Tags.HTTP_METHOD);
      if (route instanceof CharSequence) {
        withRoute(
            span,
            method instanceof CharSequence ? (CharSequence) method : null,
            (CharSequence) route);
      }
    }
  }

  private static String servletContext(final AgentSpan span) {
    final Object contextPath = span.getTag(SERVLET_CONTEXT);
    if (!(contextPath instanceof String)
        || ((String) contextPath).isEmpty()
        || "/".equals(contextPath)) {
      return null;
    }
    return (String) contextPath;
  }

  private static CharSequence prependServletContext(
      final String servletContext, final CharSequence route) {
    return servletContext == null || route == null
        ? route
        : servletContext.concat(route.toString());
  }
}
