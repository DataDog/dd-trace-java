package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A wrapping Publisher that finishes the associated span when the downstream subscriber receives
 * onComplete, onError, or cancels its subscription.
 */
public final class TracingPublisher<T> implements Publisher<T> {

  private final Publisher<T> delegate;
  private final AgentSpan span;

  public TracingPublisher(Publisher<T> delegate, AgentSpan span) {
    this.delegate = delegate;
    this.span = span;
  }

  @Override
  public void subscribe(Subscriber<? super T> subscriber) {
    delegate.subscribe(new TracingSubscriber<>(subscriber, span));
  }

  static final class TracingSubscriber<T> implements Subscriber<T> {

    private final Subscriber<? super T> delegate;
    private final AgentSpan span;
    // Guards against finishing the span more than once when terminal signals (onComplete/onError)
    // race with a downstream cancel().
    private final AtomicBoolean finished = new AtomicBoolean(false);

    TracingSubscriber(Subscriber<? super T> delegate, AgentSpan span) {
      this.delegate = delegate;
      this.span = span;
    }

    private void finishSpan() {
      if (finished.compareAndSet(false, true)) {
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }

    @Override
    public void onSubscribe(Subscription s) {
      // Wrap the subscription so a downstream cancel() (e.g. take(1), timeout, disconnect) finishes
      // the span instead of leaving it open indefinitely.
      delegate.onSubscribe(new TracingSubscription(s));
    }

    @Override
    public void onNext(T t) {
      delegate.onNext(t);
    }

    final class TracingSubscription implements Subscription {
      private final Subscription delegate;

      TracingSubscription(Subscription delegate) {
        this.delegate = delegate;
      }

      @Override
      public void request(long n) {
        delegate.request(n);
      }

      @Override
      public void cancel() {
        try {
          delegate.cancel();
        } finally {
          finishSpan();
        }
      }
    }

    @Override
    public void onError(Throwable t) {
      try {
        if (finished.compareAndSet(false, true)) {
          DECORATE.onError(span, t);
          DECORATE.beforeFinish(span);
          span.finish();
        }
      } finally {
        delegate.onError(t);
      }
    }

    @Override
    public void onComplete() {
      try {
        finishSpan();
      } finally {
        delegate.onComplete();
      }
    }
  }
}
