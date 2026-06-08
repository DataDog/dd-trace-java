package datadog.trace.instrumentation.reactor.core;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.WithAgentSpan;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.CoreSubscriber;

/**
 * Helper shared by the reactor-core instrumentations. It reads the span a user placed in the
 * Reactor context under the {@code dd.span} key (adapting it to a {@link Context}) and drives the
 * context-store-based propagation: capture on subscribe, restore on signal/blocking, and transfer
 * to optimized subscribers.
 */
public final class ReactorContextBridge {

  private static final String DD_SPAN_KEY = "dd.span";

  private ReactorContextBridge() {}

  /**
   * Records the {@link Context} derived from the {@code dd.span} span a context-writing subscriber
   * carries, into the subscriber store, once, when the subscriber is constructed. The caller
   * ({@code ContextWritingSubscriberInstrumentation}) only matches context-writing subscribers, so
   * no runtime type check is needed.
   */
  public static void captureSubscriberContext(
      final CoreSubscriber<?> subscriber,
      final ContextStore<Subscriber, Context> subscriberContexts) {
    final Context context = explicitContextFromSubscriber(subscriber);
    if (context != null) {
      subscriberContexts.put(subscriber, context);
    }
  }

  /**
   * Attaches the context recorded for {@code subscriber} by {@link #captureSubscriberContext}. A
   * plain store lookup — no {@code instanceof}, no {@code currentContext()} call — on the signal
   * hot path.
   */
  public static ContextScope activateStoredContext(
      final Subscriber<?> subscriber, final ContextStore<Subscriber, Context> subscriberContexts) {
    return attachIfRequired(subscriberContexts.get(subscriber), Context.current());
  }

  /**
   * On subscribe, hands the explicit context recorded for {@code subscriber} (a context-writing
   * subscriber) to the publisher store so the reactive-streams layer can propagate it, and attaches
   * it.
   */
  public static ContextScope captureOnSubscribe(
      final Publisher<?> publisher,
      final Subscriber<?> subscriber,
      final ContextStore<Publisher, Context> publisherContexts,
      final ContextStore<Subscriber, Context> subscriberContexts) {
    final Context context = subscriberContexts.get(subscriber);
    if (context == null) {
      return null;
    }

    publisherContexts.put(publisher, context);
    return attachIfRequired(context, Context.current());
  }

  public static ContextScope activateForBlocking(
      final Publisher<?> publisher, final ContextStore<Publisher, Context> publisherContexts) {
    return attachIfRequired(publisherContexts.get(publisher), Context.current());
  }

  public static void transferToOptimizedSubscriber(
      final Publisher<?> publisher,
      final Subscriber<?> source,
      final Subscriber<?> target,
      final ContextStore<Publisher, Context> publisherContexts,
      final ContextStore<Subscriber, Context> subscriberContexts) {
    if (source == null || target == null) {
      return;
    }

    Context context = publisherContexts.get(publisher);
    if (context == null) {
      context = subscriberContexts.get(source);
    }
    if (context != null) {
      subscriberContexts.putIfAbsent(target, context);
    }
  }

  private static Context explicitContextFromSubscriber(final CoreSubscriber<?> subscriber) {
    final reactor.util.context.Context reactorContext = currentContext(subscriber);
    if (reactorContext == null || !hasKey(reactorContext, DD_SPAN_KEY)) {
      return null;
    }
    final Object maybeSpan = get(reactorContext, DD_SPAN_KEY);
    return maybeSpan instanceof WithAgentSpan ? ((WithAgentSpan) maybeSpan).asAgentSpan() : null;
  }

  private static reactor.util.context.Context currentContext(final CoreSubscriber<?> subscriber) {
    if (subscriber == null) {
      return null;
    }
    try {
      return subscriber.currentContext();
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static ContextScope attachIfRequired(final Context context, final Context activeContext) {
    if (context == null || context == activeContext || context == Context.root()) {
      return null;
    }
    return context.attach();
  }

  private static boolean hasKey(final reactor.util.context.Context context, final Object key) {
    try {
      return context.hasKey(key);
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static Object get(final reactor.util.context.Context context, final Object key) {
    try {
      return context.get(key);
    } catch (Throwable ignored) {
      return null;
    }
  }
}
