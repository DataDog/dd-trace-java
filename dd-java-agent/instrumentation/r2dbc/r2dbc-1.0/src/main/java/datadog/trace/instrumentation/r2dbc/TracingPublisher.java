package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A wrapping Publisher that finishes the associated span when the downstream subscriber receives
 * onComplete or onError.
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

    TracingSubscriber(Subscriber<? super T> delegate, AgentSpan span) {
      this.delegate = delegate;
      this.span = span;
    }

    @Override
    public void onSubscribe(Subscription s) {
      delegate.onSubscribe(s);
    }

    @Override
    public void onNext(T t) {
      delegate.onNext(t);
    }

    @Override
    public void onError(Throwable t) {
      try {
        DECORATE.onError(span, t);
        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        delegate.onError(t);
      }
    }

    @Override
    public void onComplete() {
      try {
        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        delegate.onComplete();
      }
    }
  }
}
