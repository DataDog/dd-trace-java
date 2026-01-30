package datadog.trace.instrumentation.rxjava2;

import datadog.context.Context;
import datadog.context.ContextScope;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

/** Wrapper that makes sure spans from observer events treat the captured span as their parent. */
public final class TracingObserver<T> implements Observer<T> {
  private final Observer<T> observer;
  private final Context parentSpan;

  public TracingObserver(final Observer<T> observer, final Context parentSpan) {
    this.observer = observer;
    this.parentSpan = parentSpan;
  }

  @Override
  public void onSubscribe(final Disposable d) {
    observer.onSubscribe(d);
  }

  @Override
  public void onNext(final T value) {
    try (final ContextScope scope = parentSpan.attach()) {
      observer.onNext(value);
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
