package datadog.trace.instrumentation.pekkohttp;

import static datadog.context.ContextKey.named;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static java.util.Collections.singletonList;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.apache.pekko.http.scaladsl.model.Uri;

/**
 * Route contributions owned by one directive invocation, with immutable history shared by child
 * directives.
 */
public final class PekkoHttpRouteState {
  private static final boolean OTEL_SEMANTICS = Config.get().isTraceOtelSemanticsEnabled();
  private static final ContextKey<PekkoHttpRouteState> KEY = named("pekko-http-route");
  private static final boolean ROUTE_CAPTURE_ENABLED =
      InstrumenterConfig.get()
          .isIntegrationEnabled(
              singletonList("pekko-http-route"),
              InstrumenterConfig.get().isIntegrationsEnabled() && OTEL_SEMANTICS);

  Uri.Path remaining;
  Part part;

  PekkoHttpRouteState(Uri.Path remaining) {
    this.remaining = remaining;
  }

  public static Context init(Context context, Uri.Path requestPath) {
    return ROUTE_CAPTURE_ENABLED
        ? context.with(KEY, new PekkoHttpRouteState(requestPath))
        : context;
  }

  static PekkoHttpRouteState current() {
    return Context.current().get(KEY);
  }

  Context storeIn(Context context) {
    return context.with(KEY, this);
  }

  PekkoHttpRouteState fork() {
    PekkoHttpRouteState child = new PekkoHttpRouteState(remaining);
    child.part = part;
    return child;
  }

  void select(PekkoHttpRouteState child) {
    part = child.part;
    remaining = child.remaining;
  }

  void matched(Uri.Path before, Uri.Path after, String literal) {
    if (!before.equals(remaining)) {
      // Lookahead matchers can inspect input that a later directive consumes again.
      unmatched(before);
      if (!before.equals(remaining)) {
        return;
      }
    }
    part = new Part(before, literal, part);
    remaining = after;
  }

  void unmatched(Uri.Path before) {
    // A composed matcher can fail after some of its leaves have consumed input.
    for (Part cursor = part; cursor != null; cursor = cursor.previous) {
      if (cursor.before.equals(before)) {
        part = cursor.previous;
        remaining = before;
        return;
      }
    }
  }

  String template() {
    if (part == null) {
      return null;
    }
    int length = 0;
    for (Part cursor = part; cursor != null; cursor = cursor.previous) {
      length += cursor.literal.length();
    }
    char[] chars = new char[length];
    for (Part cursor = part; cursor != null; cursor = cursor.previous) {
      String literal = cursor.literal;
      length -= literal.length();
      literal.getChars(0, literal.length(), chars, length);
    }
    return new String(chars);
  }

  /** Publishes only the selected route, before normal HTTP response naming. */
  public static void onComplete(Context context, AgentSpan span) {
    PekkoHttpRouteState state = context.get(KEY);
    if (state == null) {
      return;
    }
    String template = state.template();
    state.part = null;
    state.remaining = null;
    if (template == null) {
      return;
    }
    AgentSpan root = span.getLocalRootSpan();
    if (root == null) {
      root = span;
    }
    Object method =
        root.getTag(OTEL_SEMANTICS ? KnownTags.HTTP_METHOD_OTEL_NAME : Tags.HTTP_METHOD);
    HTTP_RESOURCE_DECORATOR.withRoute(
        root, method == null ? null : method.toString(), template, true);
  }

  static final class Part {
    final Uri.Path before;
    final String literal;
    final Part previous;

    Part(Uri.Path before, String literal, Part previous) {
      this.before = before;
      this.literal = literal;
      this.previous = previous;
    }
  }
}
