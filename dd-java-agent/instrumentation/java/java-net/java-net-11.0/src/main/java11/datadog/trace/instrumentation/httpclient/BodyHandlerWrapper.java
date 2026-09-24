package datadog.trace.instrumentation.httpclient;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class BodyHandlerWrapper<T> implements BodyHandler<T> {
  private final BodyHandler<T> delegate;
  private final AgentSpan span;

  public BodyHandlerWrapper(BodyHandler<T> delegate, AgentSpan span) {
    this.delegate = delegate;
    this.span = span;
  }

  @Override
  public BodySubscriber<T> apply(ResponseInfo responseInfo) {
    // Capture the continuation lazily here rather than at sendAsync() call time.
    BodySubscriber<T> subscriber = delegate.apply(responseInfo);
    if (subscriber instanceof BodySubscriberWrapper) {
      return subscriber;
    }
    return new BodySubscriberWrapper<>(subscriber, span.captureWithContext().hold());
  }

  static class BodySubscriberWrapper<T> implements BodySubscriber<T> {
    private static final AtomicReferenceFieldUpdater<BodySubscriberWrapper, ContextContinuation>
        CONTINUATION =
            AtomicReferenceFieldUpdater.newUpdater(
                BodySubscriberWrapper.class, ContextContinuation.class, "continuation");

    private final BodySubscriber<T> delegate;
    private volatile ContextContinuation continuation;

    public BodySubscriberWrapper(BodySubscriber<T> delegate, ContextContinuation continuation) {
      this.delegate = delegate;
      this.continuation = continuation;
    }

    public BodySubscriber<T> getDelegate() {
      return delegate;
    }

    @Override
    public CompletionStage<T> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      boolean completed = false;
      try {
        delegate.onSubscribe(new SubscriptionWrapper(subscription, this));
        completed = true;
      } finally {
        if (!completed) {
          releaseContinuation();
        }
      }
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      boolean completed = false;
      try {
        try (ContextScope ignore = resumeContinuation()) {
          delegate.onNext(item);
        }
        completed = true;
      } finally {
        if (!completed) {
          releaseContinuation();
        }
      }
    }

    @Override
    public void onError(Throwable throwable) {
      try {
        try (ContextScope ignore = resumeContinuation()) {
          delegate.onError(throwable);
        }
      } finally {
        releaseContinuation();
      }
    }

    @Override
    public void onComplete() {
      try {
        try (ContextScope ignore = resumeContinuation()) {
          delegate.onComplete();
        }
      } finally {
        releaseContinuation();
      }
    }

    private ContextScope resumeContinuation() {
      ContextContinuation continuation = this.continuation;
      return continuation == null ? null : continuation.resume();
    }

    private void releaseContinuation() {
      ContextContinuation continuation = CONTINUATION.getAndSet(this, null);
      if (continuation != null) {
        continuation.release();
      }
    }
  }

  static final class SubscriptionWrapper implements Flow.Subscription {
    private final Flow.Subscription delegate;
    private final BodySubscriberWrapper<?> subscriber;

    SubscriptionWrapper(Flow.Subscription delegate, BodySubscriberWrapper<?> subscriber) {
      this.delegate = delegate;
      this.subscriber = subscriber;
    }

    @Override
    public void request(long count) {
      delegate.request(count);
    }

    @Override
    public void cancel() {
      try {
        delegate.cancel();
      } finally {
        subscriber.releaseContinuation();
      }
    }
  }
}
