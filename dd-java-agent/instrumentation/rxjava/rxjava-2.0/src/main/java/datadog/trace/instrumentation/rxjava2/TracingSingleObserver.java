package datadog.trace.instrumentation.rxjava2;

import datadog.context.Context;
import datadog.context.ContextScope;
import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import javax.annotation.Nonnull;

/** Wrapper that makes sure spans from observer events treat the captured span as their parent. */
public final class TracingSingleObserver<T> implements SingleObserver<T> {
  private final SingleObserver<T> observer;
  private final Context parentContext;

  public TracingSingleObserver(
      @Nonnull final SingleObserver<T> observer, @Nonnull final Context parentContext) {
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
