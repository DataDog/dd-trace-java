package datadog.trace.instrumentation.rxjava3;

import datadog.context.Context;
import datadog.context.ContextScope;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Wrapper that makes sure spans from completable-observer events treat the captured span as their
 * parent.
 */
public final class TracingCompletableObserver implements CompletableObserver {
  private final CompletableObserver observer;
  private final Context parentContext;

  public TracingCompletableObserver(
      final CompletableObserver observer, final Context parentContext) {
    this.observer = observer;
    this.parentContext = parentContext;
  }

  @Override
  public void onSubscribe(final Disposable d) {
    observer.onSubscribe(d);
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
