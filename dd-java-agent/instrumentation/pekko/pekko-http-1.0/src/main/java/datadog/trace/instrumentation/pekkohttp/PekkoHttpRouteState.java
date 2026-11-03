package datadog.trace.instrumentation.pekkohttp;

import static datadog.context.ContextKey.named;
import static datadog.trace.bootstrap.instrumentation.api.OtelHttpSemantics.spanNameMethod;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.api.Config;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Request-local route state, independent of the HTTP routing DSL. */
public final class PekkoHttpRouteState {
  private static final boolean OTEL_SEMANTICS = Config.get().isTraceOtelSemanticsEnabled();
  private static final ContextKey<PekkoHttpRouteState> KEY = named("pekko-http-route");

  Route route;

  /** Whether the latest route candidate has matched without subsequently being rejected. */
  boolean routeMatched;

  private PekkoHttpRouteState() {}

  public static Context init(Context context) {
    return OTEL_SEMANTICS ? context.with(KEY, new PekkoHttpRouteState()) : context;
  }

  static PekkoHttpRouteState current() {
    return Context.current().get(KEY);
  }

  public static void onRejected() {
    PekkoHttpRouteState state = current();
    if (state != null) {
      state.routeMatched = false;
    }
  }

  /** Drops a route candidate rejected by path matching or a later directive. */
  public static void dropRejectedRoute(Context context, AgentSpan span) {
    PekkoHttpRouteState state = context.get(KEY);
    if (state == null || state.route == null || state.routeMatched) {
      return;
    }
    state.route = null;
    AgentSpan root = span.getLocalRootSpan();
    if (root == null) {
      root = span;
    }
    root.setTag(KnownTags.HTTP_ROUTE_ID, (String) null);
    Object method = root.getTag(KnownTags.HTTP_METHOD_OTEL_NAME);
    // restore the method-only resource that OTel semantics give unrouted requests
    root.setResourceName(
        spanNameMethod(method == null ? null : method.toString()), HTTP_FRAMEWORK_ROUTE);
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
