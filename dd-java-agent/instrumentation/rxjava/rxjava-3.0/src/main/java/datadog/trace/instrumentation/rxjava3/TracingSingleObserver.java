package datadog.trace.instrumentation.rxjava3;

import datadog.context.Context;
import datadog.context.ContextScope;
import io.reactivex.rxjava3.core.SingleObserver;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Wrapper that makes sure spans from single-observer events treat the captured span as their
 * parent.
 */
public final class TracingSingleObserver<T> implements SingleObserver<T> {
  private final SingleObserver<T> observer;
  private final Context parentContext;

  public TracingSingleObserver(final SingleObserver<T> observer, final Context parentContext) {
    this.observer = observer;
    this.parentContext = parentContext;
  }

  @Override
  public void onSubscribe(final Disposable d) {
    observer.onSubscribe(d);
  }

  @Override
  public void onSuccess(final T value) {
    try (final ContextScope scope = parentContext.attach()) {
      observer.onSuccess(value);
    }
  }

  @Override
  public void onError(final Throwable e) {
    try (final ContextScope scope = parentContext.attach()) {
      observer.onError(e);
    }
  }
}
