package datadog.trace.instrumentation.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Proxy;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class BodyHandlerWrapperTest {

  @Test
  void releasesContinuationWhenSubscriptionIsCancelled() {
    RecordingContinuation continuation = new RecordingContinuation();
    AgentSpan span =
        (AgentSpan)
            Proxy.newProxyInstance(
                AgentSpan.class.getClassLoader(),
                new Class<?>[] {AgentSpan.class},
                (proxy, method, args) -> continuation);
    RecordingSubscriber subscriber = new RecordingSubscriber();
    BodySubscriber<Void> wrapper =
        new BodyHandlerWrapper<>(ignored -> subscriber, span).apply(null);
    RecordingSubscription subscription = new RecordingSubscription();

    wrapper.onSubscribe(subscription);
    subscriber.subscription.request(3);
    subscriber.subscription.cancel();
    wrapper.onComplete();

    assertEquals(3, subscription.requested);
    assertEquals(1, subscription.cancelled);
    assertEquals(1, continuation.released);
  }

  private static final class RecordingSubscriber
      implements java.net.http.HttpResponse.BodySubscriber<Void> {
    private final CompletableFuture<Void> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<Void> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
    }

    @Override
    public void onNext(List<ByteBuffer> item) {}

    @Override
    public void onError(Throwable throwable) {
      body.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
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
