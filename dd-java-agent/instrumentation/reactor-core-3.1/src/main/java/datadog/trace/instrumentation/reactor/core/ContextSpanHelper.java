package datadog.trace.instrumentation.reactor.core;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.WithAgentSpan;
import javax.annotation.Nullable;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

public class ContextSpanHelper {
  private static final String DD_SPAN_KEY = "dd.span";

  private ContextSpanHelper() {}

  @Nullable
  public static AgentSpan extractSpanFromSubscriberContext(final CoreSubscriber<?> subscriber) {
    if (subscriber == null) {
      return null;
    }
    Context context = null;
    try {
      context = subscriber.currentContext();
    } catch (Throwable ignored) {
    }
    if (context == null) {
      return null;
    }
    if (context.hasKey(DD_SPAN_KEY)) {
      Object maybeSpan = context.get(DD_SPAN_KEY);
      if (maybeSpan instanceof WithAgentSpan) {
        return ((WithAgentSpan) maybeSpan).asAgentSpan();
      }
    }
    return null;
  }
}
