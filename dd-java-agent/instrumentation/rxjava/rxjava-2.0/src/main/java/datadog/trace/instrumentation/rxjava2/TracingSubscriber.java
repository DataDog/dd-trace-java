package datadog.trace.instrumentation.rxjava2;

import datadog.context.Context;
import datadog.context.ContextScope;
import javax.annotation.Nonnull;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/** Wrapper that makes sure spans from subscriber events treat the captured span as their parent. */
public final class TracingSubscriber<T> implements Subscriber<T> {
  private final Subscriber<T> subscriber;
  private final Context parentSpan;

  public TracingSubscriber(
      @Nonnull final Subscriber<T> subscriber, @Nonnull final Context parentSpan) {
    this.subscriber = subscriber;
    this.parentSpan = parentSpan;
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    subscriber.onSubscribe(subscription);
  }

  @Override
  public void onNext(final T value) {
    try (final ContextScope scope = parentSpan.attach()) {
      subscriber.onNext(value);
    }
  }

  @Override
  public void onError(final Throwable e) {
    try (final ContextScope scope = parentSpan.attach()) {
      subscriber.onError(e);
    }
  }

  @Override
  public void onComplete() {
    try (final ContextScope scope = parentSpan.attach()) {
      subscriber.onComplete();
    }
  }
}
