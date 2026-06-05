package datadog.trace.instrumentation.reactivestreams;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

public final class ReactiveStreamsContextPropagation {

  private ReactiveStreamsContextPropagation() {}

  public static ContextScope captureOnSubscribe(
      final Publisher<?> publisher,
      final Subscriber<?> subscriber,
      final ContextStore<Publisher, Context> publisherContexts,
      final ContextStore<Subscriber, Context> subscriberContexts) {
    if (subscriber == null) {
      return null;
    }

    final Context contextFromPublisher = publisherContexts.remove(publisher);
    final Context activeContext = Java8BytecodeBridge.getCurrentContext();
    final Context context =
        contextFromPublisher != null ? contextFromPublisher : nonRootContext(activeContext);
    if (context == null) {
      return null;
    }

    final Context subscriberContext = subscriberContexts.putIfAbsent(subscriber, context);
    // A context captured on the publisher (cross-thread propagation) must win even when the
    // current thread already carries a non-root active context
    return attachIfRequired(subscriberContext, activeContext, true);
  }

  public static ContextScope activateOnSignal(
      final Subscriber<?> subscriber, final ContextStore<Subscriber, Context> subscriberContexts) {
    final Context activeContext = Java8BytecodeBridge.getCurrentContext();
    if (nonRootContext(activeContext) != null) {
      return null;
    }
    return attachIfRequired(subscriberContexts.get(subscriber), activeContext, false);
  }

  public static ContextScope activateOnComplete(
      final Subscriber<?> subscriber, final ContextStore<Subscriber, Context> subscriberContexts) {
    return attachIfRequired(
        subscriberContexts.get(subscriber), Java8BytecodeBridge.getCurrentContext(), true);
  }

  private static ContextScope attachIfRequired(
      final Context context, final Context activeContext, final boolean allowReplacingActive) {
    if (nonRootContext(context) == null || context == activeContext) {
      return null;
    }
    if (!allowReplacingActive && nonRootContext(activeContext) != null) {
      return null;
    }
    return context.attach();
  }

  private static Context nonRootContext(final Context context) {
    return context == null || context == Java8BytecodeBridge.getRootContext() ? null : context;
  }
}
