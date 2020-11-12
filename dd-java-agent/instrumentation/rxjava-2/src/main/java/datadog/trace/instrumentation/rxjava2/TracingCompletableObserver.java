package datadog.trace.instrumentation.rxjava2;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import io.reactivex.CompletableObserver;
import io.reactivex.disposables.Disposable;

public final class TracingCompletableObserver implements CompletableObserver {
  private final CompletableObserver observer;
  private final AgentSpan parentSpan;

  public TracingCompletableObserver(
      final CompletableObserver observer, final AgentSpan parentSpan) {
    this.observer = observer;
    this.parentSpan = parentSpan;
  }

  @Override
  public void onSubscribe(final Disposable d) {
    observer.onSubscribe(d);
  }

  @Override
  public void onError(final Throwable e) {
    try (final TraceScope scope = activateSpan(parentSpan)) {
      observer.onError(e);
    }
  }

  @Override
  public void onComplete() {
    try (final TraceScope scope = activateSpan(parentSpan)) {
      observer.onComplete();
    }
  }
}
