package datadog.trace.instrumentation.rxjava3;

import datadog.context.Context;
import datadog.context.ContextScope;
import io.reactivex.rxjava3.core.FlowableSubscriber;
import javax.annotation.Nonnull;
import org.reactivestreams.Subscription;

/** Wrapper that makes sure spans from subscriber events treat the captured span as their parent. */
public final class TracingSubscriber<T> implements FlowableSubscriber<T> {
  private final FlowableSubscriber<T> subscriber;
  private final Context parentContext;

  public TracingSubscriber(
      @Nonnull final FlowableSubscriber<T> subscriber, @Nonnull final Context parentContext) {
    this.subscriber = subscriber;
    this.parentContext = parentContext;
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    subscriber.onSubscribe(subscription);
  }

  @Override
  public void onNext(final T value) {
    try (final ContextScope scope = parentContext.attach()) {
      subscriber.onNext(value);
    }
  }

  @Override
  public void onError(final Throwable e) {
    try (final ContextScope scope = parentContext.attach()) {
      subscriber.onError(e);
    }
  }

  @Override
  public void onComplete() {
    try (final ContextScope scope = parentContext.attach()) {
      subscriber.onComplete();
    }
  }
}
