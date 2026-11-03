package datadog.trace.instrumentation.akkahttp;

import static datadog.context.ContextKey.named;
import static datadog.trace.bootstrap.instrumentation.api.OtelHttpSemantics.spanNameMethod;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.api.Config;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Request-local route state, independent of the HTTP routing DSL. */
public final class AkkaHttpRouteState {
  private static final boolean OTEL_SEMANTICS = Config.get().isTraceOtelSemanticsEnabled();
  private static final ContextKey<AkkaHttpRouteState> KEY = named("akka-http-route");

  Route route;

  /** Whether the last outermost path matcher tried matched; OTel drops the route otherwise. */
  boolean lastMatched;

  private AkkaHttpRouteState() {}

  public static Context init(Context context) {
    return OTEL_SEMANTICS ? context.with(KEY, new AkkaHttpRouteState()) : context;
  }

  static AkkaHttpRouteState current() {
    return Context.current().get(KEY);
  }

  /** Drops the route of a request rejected after its last path matcher failed. */
  public static void dropRejectedRoute(Context context, AgentSpan span) {
    AkkaHttpRouteState state = context.get(KEY);
    if (state == null || state.route == null || state.lastMatched) {
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
