package datadog.trace.instrumentation.rxjava2;

import datadog.context.Context;
import datadog.context.ContextScope;
import io.reactivex.CompletableObserver;
import io.reactivex.disposables.Disposable;
import javax.annotation.Nonnull;

/** Wrapper that makes sure spans from observer events treat the captured span as their parent. */
public final class TracingCompletableObserver implements CompletableObserver {
  private final CompletableObserver observer;
  private final Context parentSpan;

  public TracingCompletableObserver(
      @Nonnull final CompletableObserver observer, @Nonnull final Context parentSpan) {
    this.observer = observer;
    this.parentSpan = parentSpan;
  }

  @Override
  public void onSubscribe(final Disposable d) {
    observer.onSubscribe(d);
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
