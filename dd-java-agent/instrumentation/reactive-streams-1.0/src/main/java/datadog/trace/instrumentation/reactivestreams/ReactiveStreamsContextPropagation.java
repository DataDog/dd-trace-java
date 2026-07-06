package datadog.trace.instrumentation.reactivestreams;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.ContextStore;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

public final class ReactiveStreamsContextPropagation {

  private ReactiveStreamsContextPropagation() {}

  public static ContextScope captureOnSubscribe(
      final Publisher<?> publisher,
      final Subscriber<?> subscriber,
      final ContextStore<Publisher, Context> publisherContexts,
      final ContextStore<Subscriber, Context> subscriberContexts) {
    // Don't consume the publisher context until we've verified the subscriber is non-null. For
    // subscribe(null), Reactive Streams mandates an NPE after this advice returns. Consuming the
    // context earlier would incorrectly discard it.
    if (subscriber == null) {
      return null;
    }

    final Context contextFromPublisher = publisherContexts.remove(publisher);
    final Context activeContext = Context.current();
    final Context context = contextFromPublisher != null ? contextFromPublisher : activeContext;
    if (context == Context.root()) {
      return null;
    }

    final Context subscriberContext = subscriberContexts.putIfAbsent(subscriber, context);
    // A context captured on the publisher (cross-thread propagation) must win even when the
    // current thread already carries a non-root active context.
    return attachIfRequired(subscriberContext, activeContext);
  }

  public static ContextScope activateOnSignal(
      final Subscriber<?> subscriber, final ContextStore<Subscriber, Context> subscriberContexts) {
    final Context activeContext = Context.current();
    if (activeContext != Context.root()) {
      return null;
    }
    return attachIfRequired(subscriberContexts.get(subscriber), activeContext);
  }

  public static ContextScope activateOnComplete(
      final Subscriber<?> subscriber, final ContextStore<Subscriber, Context> subscriberContexts) {
    return attachIfRequired(subscriberContexts.get(subscriber), Context.current());
  }

  private static ContextScope attachIfRequired(final Context context, final Context activeContext) {
    if (context == null || context == activeContext || context == Context.root()) {
      return null;
    }
    return context.attach();
  }
}
