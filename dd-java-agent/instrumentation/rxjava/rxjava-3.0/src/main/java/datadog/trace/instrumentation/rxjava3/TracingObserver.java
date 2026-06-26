package datadog.trace.instrumentation.rxjava3;

import datadog.context.Context;
import datadog.context.ContextScope;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import javax.annotation.Nonnull;

/** Wrapper that makes sure spans from observer events treat the captured span as their parent. */
public final class TracingObserver<T> implements Observer<T> {
  private final Observer<T> observer;
  private final Context parentContext;

  public TracingObserver(
      @Nonnull final Observer<T> observer, @Nonnull final Context parentContext) {
    this.observer = observer;
    this.parentContext = parentContext;
  }

  @Override
  public void onSubscribe(final Disposable d) {
    observer.onSubscribe(d);
  }

  @Override
  public void onNext(final T value) {
    try (final ContextScope scope = parentContext.attach()) {
      observer.onNext(value);
    }
  }

  @Override
  public void onError(final Throwable e) {
    try (final ContextScope scope = parentContext.attach()) {
      observer.onError(e);
    }
  }

  @Override
  public void onComplete() {
    try (final ContextScope scope = parentContext.attach()) {
      observer.onComplete();
    }
  }
}
