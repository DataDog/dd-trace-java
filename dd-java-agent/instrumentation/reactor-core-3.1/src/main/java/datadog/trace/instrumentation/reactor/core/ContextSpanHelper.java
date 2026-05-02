package datadog.trace.instrumentation.reactor.core;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.WithAgentSpan;
import javax.annotation.Nullable;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;

public class ContextSpanHelper {

  private static final Class<?> MONO_WITH_CONTEXT_CLASS = findMonoWithContextClass();

  private static final String DD_SPAN_KEY = "dd.span";

  private static Class<?> findMonoWithContextClass() {
    final ClassLoader classLoader = Mono.class.getClassLoader();
    // 3.4+
    try {
      return Class.forName(
          "reactor.core.publisher.FluxContextWrite$ContextWriteSubscriber", false, classLoader);
    } catch (Throwable ignored) {
    }
    // < 3.4
    try {
      return Class.forName(
          "reactor.core.publisher.FluxContextStart$ContextStartSubscriber", false, classLoader);
    } catch (Throwable ignored) {
    }
    return null;
  }

  private ContextSpanHelper() {}

  @Nullable
  public static Context extractContextFromSubscriberContext(final CoreSubscriber<?> subscriber) {
    if (MONO_WITH_CONTEXT_CLASS == null || !MONO_WITH_CONTEXT_CLASS.isInstance(subscriber)) {
      return null;
    }
    reactor.util.context.Context reactorContext = null;
    try {
      reactorContext = subscriber.currentContext();
    } catch (Throwable ignored) {
    }
    if (reactorContext == null) {
      return null;
    }
    if (reactorContext.hasKey(DD_SPAN_KEY)) {
      Object maybeSpan = reactorContext.get(DD_SPAN_KEY);
      if (maybeSpan instanceof WithAgentSpan) {
        return ((WithAgentSpan) maybeSpan).asAgentSpan();
      }
    }
    return null;
  }
}
