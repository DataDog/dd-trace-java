package datadog.trace.instrumentation.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Proxy;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class BodyHandlerWrapperTest {

  @Test
  void holdsContextAcrossCallbacksUntilCompletion() {
    Context original = Context.current();
    Context captured = original.with(ContextKey.named("response-body"), new Object());
    ContextContinuation continuation = captured.capture();
    RecordingSubscriber subscriber = new RecordingSubscriber();
    BodySubscriber<Void> wrapper = wrap(subscriber, continuation);

    try {
      wrapper.onNext(List.of());
      assertSame(captured, subscriber.callbackContexts.get(0));
      assertSame(original, Context.current());

      wrapper.onNext(List.of());
      assertSame(captured, subscriber.callbackContexts.get(1));
      assertSame(original, Context.current());

      wrapper.onComplete();
      assertSame(captured, subscriber.callbackContexts.get(2));
      assertSame(original, Context.current());

      // A released continuation must no longer reactivate the captured context.
      try (ContextScope ignored = continuation.resume()) {
        assertSame(original, Context.current());
      }
    } finally {
      continuation.release();
    }
  }

  @Test
  void releasesContinuationWhenOnSubscribeThrows() {
    RecordingContinuation continuation = new RecordingContinuation();
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.throwOnSubscribe = true;
    BodySubscriber<Void> wrapper = wrap(subscriber, continuation);

    assertThrows(
        IllegalStateException.class, () -> wrapper.onSubscribe(new RecordingSubscription()));
    assertEquals(1, continuation.released);
    wrapper.onComplete();

    assertEquals(1, continuation.released);
  }

  @Test
  void releasesContinuationWhenOnNextThrows() {
    RecordingContinuation continuation = new RecordingContinuation();
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.throwOnNext = true;
    BodySubscriber<Void> wrapper = wrap(subscriber, continuation);

    assertThrows(IllegalStateException.class, () -> wrapper.onNext(List.of()));
    assertEquals(1, continuation.released);
    wrapper.onComplete();

    assertEquals(1, continuation.released);
  }

  @Test
  void releasesContinuationWhenSubscriptionIsCancelled() {
    RecordingContinuation continuation = new RecordingContinuation();
    RecordingSubscriber subscriber = new RecordingSubscriber();
    BodySubscriber<Void> wrapper = wrap(subscriber, continuation);
    RecordingSubscription subscription = new RecordingSubscription();

    wrapper.onSubscribe(subscription);
    subscriber.subscription.request(3);
    subscriber.subscription.cancel();
    wrapper.onComplete();

    assertEquals(3, subscription.requested);
    assertEquals(1, subscription.cancelled);
    assertEquals(1, continuation.released);
  }

  private static BodySubscriber<Void> wrap(
      RecordingSubscriber subscriber, ContextContinuation continuation) {
    AgentSpan span =
        (AgentSpan)
            Proxy.newProxyInstance(
                AgentSpan.class.getClassLoader(),
                new Class<?>[] {AgentSpan.class},
                (proxy, method, args) -> continuation);
    return new BodyHandlerWrapper<>(ignored -> subscriber, span).apply(null);
  }

  private static final class RecordingSubscriber
      implements java.net.http.HttpResponse.BodySubscriber<Void> {
    private final CompletableFuture<Void> body = new CompletableFuture<>();
    private final List<Context> callbackContexts = new ArrayList<>();
    private Flow.Subscription subscription;
    private boolean throwOnSubscribe;
    private boolean throwOnNext;

    @Override
    public CompletionStage<Void> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      if (throwOnSubscribe) {
        throw new IllegalStateException("onSubscribe");
      }
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      callbackContexts.add(Context.current());
      if (throwOnNext) {
        throw new IllegalStateException("onNext");
      }
    }

    @Override
    public void onError(Throwable throwable) {
      body.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
      callbackContexts.add(Context.current());
      body.complete(null);
    }
  }

  private static final class RecordingSubscription implements Flow.Subscription {
    private long requested;
    private int cancelled;

    @Override
    public void request(long count) {
      requested += count;
    }

    @Override
    public void cancel() {
      cancelled++;
    }
  }

  private static final class RecordingContinuation implements ContextContinuation {
    private int released;

    @Override
    public ContextContinuation hold() {
      return this;
    }

    @Override
    public Context context() {
      return null;
    }

    @Override
    public ContextScope resume() {
      return null;
    }

    @Override
    public void release() {
      released++;
    }
  }
}
