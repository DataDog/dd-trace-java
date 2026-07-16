package datadog.trace.instrumentation.reactorcore;

import datadog.context.Context;
import datadog.context.ContextScope;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;

/** Wrapper that makes sure spans from subscriber events treat the captured span as their parent. */
public final class TracingCoreSubscriber<T> implements CoreSubscriber<T> {
  private final CoreSubscriber<T> delegate;
  private final Context parentContext;

  public TracingCoreSubscriber(final CoreSubscriber<T> delegate, final Context parentContext) {
    this.delegate = delegate;
    this.parentContext = parentContext;
  }

  @Override
  public void onSubscribe(final Subscription s) {
    delegate.onSubscribe(s);
  }

  @Override
  public void onNext(final T value) {
    try (final ContextScope scope = parentContext.attach()) {
      delegate.onNext(value);
    }
  }

  @Override
  public void onError(final Throwable t) {
    try (final ContextScope scope = parentContext.attach()) {
      delegate.onError(t);
    }
  }

  @Override
  public void onComplete() {
    try (final ContextScope scope = parentContext.attach()) {
      delegate.onComplete();
    }
  }
}
