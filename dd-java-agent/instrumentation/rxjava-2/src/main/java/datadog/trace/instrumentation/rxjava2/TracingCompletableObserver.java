package datadog.trace.instrumentation.rxjava2;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import io.reactivex.CompletableObserver;
import io.reactivex.disposables.Disposable;

public final class TracingCompletableObserver implements CompletableObserver {
  private final CompletableObserver observer;
  private final AgentSpan span;

  public TracingCompletableObserver(final CompletableObserver observer, final AgentSpan span) {
    this.observer = observer;
    this.span = span;
  }

  @Override
  public void onSubscribe(final Disposable d) {
    observer.onSubscribe(d);
  }

  @Override
  public void onError(final Throwable e) {
    try (final TraceScope scope = AgentTracer.activateSpan(span)) {
      observer.onError(e);
    }
  }

  @Override
  public void onComplete() {
    try (final TraceScope scope = AgentTracer.activateSpan(span)) {
      observer.onComplete();
    }
  }
}
