package datadog.trace.instrumentation.akkahttp;

import static datadog.context.ContextKey.named;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static java.util.Collections.singletonList;

import akka.http.scaladsl.model.Uri;
import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

/** Request-local route state, independent of the HTTP routing DSL. */
public final class AkkaHttpRouteState {
  private static final boolean OTEL_SEMANTICS_ENABLED = Config.get().isTraceOtelSemanticsEnabled();
  private static final ContextKey<AkkaHttpRouteState> KEY = named("akka-http-route");

  private static final boolean ROUTE_CAPTURE_ENABLED =
      InstrumenterConfig.get()
          .isIntegrationEnabled(
              singletonList("akka-http-route"),
              InstrumenterConfig.get().isIntegrationsEnabled() && OTEL_SEMANTICS_ENABLED);

  String requestPath;
  Route route;
  CharSequence originalResourceName;

  /** Whether the latest route candidate has matched without subsequently being rejected. */
  boolean routeMatched;

  AkkaHttpRouteState(String requestPath) {
    this.requestPath = requestPath;
  }

  public static Context init(Context context, Uri.Path requestPath) {
    return ROUTE_CAPTURE_ENABLED
        ? context.with(KEY, new AkkaHttpRouteState(requestPath.toString()))
        : context;
  }

  static AkkaHttpRouteState current() {
    return Context.current().get(KEY);
  }

  public static void onRejected() {
    AkkaHttpRouteState state = current();
    if (state != null) {
      state.routeMatched = false;
    }
  }

  /** Drops rejected candidates and releases route history after request completion. */
  public static void onComplete(Context context, AgentSpan span) {
    AkkaHttpRouteState state = context.get(KEY);
    if (state == null) {
      return;
    }
    state.requestPath = null;
    Route route = state.route;
    state.route = null;
    CharSequence originalResourceName = state.originalResourceName;
    state.originalResourceName = null;
    if (route == null) {
      return;
    }
    AgentSpan root = span.getLocalRootSpan();
    if (root == null) {
      root = span;
    }
    if (state.routeMatched) {
      if (!OTEL_SEMANTICS_ENABLED) {
        Object method = root.getTag(Tags.HTTP_METHOD);
        HTTP_RESOURCE_DECORATOR.withRoute(
            root, method == null ? null : method.toString(), route.template, true);
      }
      return;
    }
    root.setTag(KnownTags.HTTP_ROUTE_ID, (String) null);
    if (originalResourceName != null) {
      // Undo tentative route naming without overriding higher-priority application names.
      root.setResourceName(originalResourceName, HTTP_FRAMEWORK_ROUTE);
    }
  }

  public static final class Route {
    final int offset;
    final String remaining;
    final String template;
    final Route previous;

    Route(int offset, String remaining, String template) {
      this(offset, remaining, template, null);
    }

    Route(int offset, String remaining, String template, Route previous) {
      this.offset = offset;
      this.remaining = remaining;
      this.template = template;
      this.previous = previous;
    }
  }
}
