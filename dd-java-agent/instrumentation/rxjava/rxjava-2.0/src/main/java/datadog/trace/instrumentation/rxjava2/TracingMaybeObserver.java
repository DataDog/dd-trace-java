package datadog.trace.instrumentation.rxjava2;

import datadog.context.Context;
import datadog.context.ContextScope;
import io.reactivex.MaybeObserver;
import io.reactivex.disposables.Disposable;
import javax.annotation.Nonnull;

/** Wrapper that makes sure spans from observer events treat the captured span as their parent. */
public final class TracingMaybeObserver<T> implements MaybeObserver<T> {
  private final MaybeObserver<T> observer;
  private final Context parentSpan;

  public TracingMaybeObserver(
      @Nonnull final MaybeObserver<T> observer, @Nonnull final Context parentSpan) {
    this.observer = observer;
    this.parentSpan = parentSpan;
  }

  @Override
  public void onSubscribe(final Disposable d) {
    observer.onSubscribe(d);
  }

  @Override
  public void onSuccess(final T value) {
    try (final ContextScope scope = parentSpan.attach()) {
      observer.onSuccess(value);
    }
  }

  @Override
  public void onError(final Throwable e) {
    try (final ContextScope scope = parentSpan.attach()) {
      observer.onError(e);
    }
  }

  @Override
  public void onComplete() {
    try (final ContextScope scope = parentSpan.attach()) {
      observer.onComplete();
    }
  }
}
