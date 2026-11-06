package datadog.trace.instrumentation.akkahttp;

import static datadog.context.ContextKey.named;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import akka.http.scaladsl.model.Uri;
import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.api.Config;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

/**
 * Route contributions owned by one directive invocation, with immutable history shared by child
 * directives.
 */
public final class AkkaHttpRouteState {
  private static final boolean OTEL_SEMANTICS = Config.get().isTraceOtelSemanticsEnabled();
  private static final ContextKey<AkkaHttpRouteState> KEY = named("akka-http-route");

  Uri.Path remaining;
  Part part;

  /** Set once this directive's result is known; later work under its context updates the parent. */
  private AkkaHttpRouteState retiredTo;

  AkkaHttpRouteState(Uri.Path remaining) {
    this.remaining = remaining;
  }

  public static Context init(Context context, Uri.Path requestPath) {
    return context.with(KEY, new AkkaHttpRouteState(requestPath));
  }

  static AkkaHttpRouteState current() {
    AkkaHttpRouteState state = Context.current().get(KEY);
    // A fallback can run under the completion context of a directive that already finished.
    while (state != null && state.retiredTo != null) {
      state = state.retiredTo;
    }
    return state;
  }

  Context storeIn(Context context) {
    return context.with(KEY, this);
  }

  AkkaHttpRouteState fork() {
    AkkaHttpRouteState child = new AkkaHttpRouteState(remaining);
    child.part = part;
    return child;
  }

  void select(AkkaHttpRouteState child) {
    part = child.part;
    remaining = child.remaining;
  }

  void retire(AkkaHttpRouteState child) {
    child.retiredTo = this;
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
    AkkaHttpRouteState state = context.get(KEY);
    if (state == null) {
      return;
    }
    String template = state.template();
    state.part = null;
    state.remaining = null;
    if (template == null) {
      return;
    }
    Object method =
        span.getTag(OTEL_SEMANTICS ? KnownTags.HTTP_METHOD_OTEL_NAME : Tags.HTTP_METHOD);
    HTTP_RESOURCE_DECORATOR.withRoute(
        span, method == null ? null : method.toString(), template, true);
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
