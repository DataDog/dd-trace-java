package datadog.trace.instrumentation.pekkohttp;

import static datadog.context.ContextKey.named;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.api.Config;

/** Request-local route state, independent of the HTTP routing DSL. */
public final class PekkoHttpRouteState {
  private static final boolean OTEL_SEMANTICS = Config.get().isTraceOtelSemanticsEnabled();
  private static final ContextKey<PekkoHttpRouteState> KEY = named("pekko-http-route");

  Route route;

  private PekkoHttpRouteState() {}

  public static Context init(Context context) {
    return OTEL_SEMANTICS ? context.with(KEY, new PekkoHttpRouteState()) : context;
  }

  static PekkoHttpRouteState current() {
    return Context.current().get(KEY);
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
