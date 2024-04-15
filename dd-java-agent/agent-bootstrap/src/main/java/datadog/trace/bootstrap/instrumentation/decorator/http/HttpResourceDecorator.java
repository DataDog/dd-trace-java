package datadog.trace.bootstrap.instrumentation.decorator.http;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.trace.api.Config;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.normalize.HttpResourceNames;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;

public class HttpResourceDecorator {
  public static final HttpResourceDecorator HTTP_RESOURCE_DECORATOR = new HttpResourceDecorator();

  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");

  private final boolean shouldSetUrlResourceName =
      Config.get().isRuleEnabled("URLAsResourceNameRule");

  private HttpResourceDecorator() {}

  // Extract this to allow for easier testing
  protected AgentTracer.TracerAPI tracer() {
    return AgentTracer.get();
  }

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
      callIGCallbackRoute(span, route);
    }
    return span;
  }

  private void callIGCallbackRoute(@Nonnull final AgentSpan span, final CharSequence resourceName) {
    CallbackProvider cbp = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
    RequestContext requestContext = span.getRequestContext();

    if (requestContext == null || cbp == null || resourceName == null) {
      return;
    }

    BiConsumer<RequestContext, String> callback = cbp.getCallback(EVENTS.requestRoute());
    if (callback == null) {
      return;
    }

    callback.accept(requestContext, resourceName.toString());
  }
}
