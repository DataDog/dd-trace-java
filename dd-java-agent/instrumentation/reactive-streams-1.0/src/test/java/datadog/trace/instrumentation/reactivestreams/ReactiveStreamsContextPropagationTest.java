package datadog.trace.instrumentation.reactivestreams;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.ContextStore;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

class ReactiveStreamsContextPropagationTest {

  private static final ContextKey<String> KEY = ContextKey.named("reactive-streams-test");

  @Test
  void publisherCapturedContextOverridesActiveContext() {
    final Publisher<Object> publisher = subscriber -> {};
    final Subscriber<Object> subscriber = new NoopSubscriber();
    final ContextStore<Publisher, Context> publisherContexts = new MapContextStore<>();
    final ContextStore<Subscriber, Context> subscriberContexts = new MapContextStore<>();

    // A context was captured on the publisher (e.g. at assembly / cross-thread subscribe).
    final Context captured = Context.root().with(KEY, "captured");
    publisherContexts.put(publisher, captured);

    // The current thread already carries a different, non-root active context.
    final Context active = Context.root().with(KEY, "active");
    try (ContextScope activeScope = active.attach()) {
      assertSame(active, Context.current());

      final ContextScope scope =
          ReactiveStreamsContextPropagation.captureOnSubscribe(
              publisher, subscriber, publisherContexts, subscriberContexts);
      try {
        // The captured context must win over the ambient active one
        assertNotNull(scope, "captured context should be attached over the active context");
        assertSame(captured, Context.current());
      } finally {
        if (scope != null) {
          scope.close();
        }
      }

      // Closing the scope restores the previously active context.
      assertSame(active, Context.current());
    }

    // The captured context is remembered for the subscriber, and removed from the publisher store.
    assertSame(captured, subscriberContexts.get(subscriber));
    assertNull(publisherContexts.get(publisher));
  }

  @Test
  void signalActivationIsSkippedWhenAnotherContextIsActive() {
    final Subscriber<Object> subscriber = new NoopSubscriber();
    final ContextStore<Subscriber, Context> subscriberContexts = new MapContextStore<>();
    subscriberContexts.put(subscriber, Context.root().with(KEY, "stored"));

    final Context active = Context.root().with(KEY, "active");
    try (ContextScope activeScope = active.attach()) {
      final ContextScope scope =
          ReactiveStreamsContextPropagation.activateOnSignal(subscriber, subscriberContexts);
      assertNull(scope, "must not override an already-active non-root context on a signal");
      assertSame(active, Context.current());
    }
  }

  @Test
  void signalActivationAttachesStoredContextWhenIdle() {
    final Subscriber<Object> subscriber = new NoopSubscriber();
    final ContextStore<Subscriber, Context> subscriberContexts = new MapContextStore<>();
    final Context stored = Context.root().with(KEY, "stored");
    subscriberContexts.put(subscriber, stored);

    final ContextScope scope =
        ReactiveStreamsContextPropagation.activateOnSignal(subscriber, subscriberContexts);
    try {
      assertNotNull(scope);
      assertSame(stored, Context.current());
    } finally {
      if (scope != null) {
        scope.close();
      }
    }
  }

  private static final class NoopSubscriber implements Subscriber<Object> {
    @Override
    public void onSubscribe(final Subscription subscription) {}

    @Override
    public void onNext(final Object value) {}

    @Override
    public void onError(final Throwable throwable) {}

    @Override
    public void onComplete() {}
  }

  private static final class MapContextStore<K, C> implements ContextStore<K, C> {
    private final Map<K, C> map = new IdentityHashMap<>();

    @Override
    public C get(final K key) {
      return map.get(key);
    }

    @Override
    public void put(final K key, final C context) {
      map.put(key, context);
    }

    @Override
    public C putIfAbsent(final K key, final C context) {
      final C existing = map.get(key);
      if (existing != null) {
        return existing;
      }
      map.put(key, context);
      return context;
    }

    @Override
    public C putIfAbsent(final K key, final Factory<C> contextFactory) {
      final C existing = map.get(key);
      if (existing != null) {
        return existing;
      }
      final C created = contextFactory.create();
      map.put(key, created);
      return created;
    }

    @Override
    public C computeIfAbsent(final K key, final KeyAwareFactory<? super K, C> contextFactory) {
      final C existing = map.get(key);
      if (existing != null) {
        return existing;
      }
      final C created = contextFactory.create(key);
      map.put(key, created);
      return created;
    }

    @Override
    public C remove(final K key) {
      return map.remove(key);
    }
  }
}
