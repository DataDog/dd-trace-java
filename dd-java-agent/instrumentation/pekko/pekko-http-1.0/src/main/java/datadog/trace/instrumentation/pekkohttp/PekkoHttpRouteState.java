package datadog.trace.instrumentation.pekkohttp;

import static datadog.context.ContextKey.named;
import static datadog.trace.bootstrap.instrumentation.api.OtelHttpSemantics.spanNameMethod;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE;
import static java.util.Collections.singletonList;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.pekko.http.scaladsl.model.Uri;

/** Request-local route state, independent of the HTTP routing DSL. */
public final class PekkoHttpRouteState {
  private static final boolean OTEL_SEMANTICS_ENABLED = Config.get().isTraceOtelSemanticsEnabled();
  private static final ContextKey<PekkoHttpRouteState> KEY = named("pekko-http-route");

  private static final boolean ROUTE_CAPTURE_ENABLED =
      InstrumenterConfig.get()
          .isIntegrationEnabled(
              singletonList("pekko-http-route"),
              InstrumenterConfig.get().isIntegrationsEnabled() && OTEL_SEMANTICS_ENABLED);

  String requestPath;
  Route route;

  /** Whether the latest route candidate has matched without subsequently being rejected. */
  boolean routeMatched;

  PekkoHttpRouteState(String requestPath) {
    this.requestPath = requestPath;
  }

  public static Context init(Context context, Uri.Path requestPath) {
    return ROUTE_CAPTURE_ENABLED
        ? context.with(KEY, new PekkoHttpRouteState(requestPath.toString()))
        : context;
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

  /** Drops rejected candidates and releases route history after request completion. */
  public static void onComplete(Context context, AgentSpan span) {
    PekkoHttpRouteState state = context.get(KEY);
    if (state == null) {
      return;
    }
    state.requestPath = null;
    boolean rejected = state.route != null && !state.routeMatched;
    state.route = null;
    if (!rejected) {
      return;
    }
    AgentSpan root = span.getLocalRootSpan();
    if (root == null) {
      root = span;
    }
    root.setTag(KnownTags.HTTP_ROUTE_ID, (String) null);
    if (OTEL_SEMANTICS_ENABLED) {
      Object method = root.getTag(KnownTags.HTTP_METHOD_OTEL_NAME);
      // Restore the method-only resource that OTel semantics give unrouted requests.
      root.setResourceName(
          spanNameMethod(method == null ? null : method.toString()), HTTP_FRAMEWORK_ROUTE);
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
