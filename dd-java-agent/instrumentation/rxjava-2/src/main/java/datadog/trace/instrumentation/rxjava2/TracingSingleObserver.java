package datadog.trace.instrumentation.rxjava2;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;

public final class TracingSingleObserver<T> implements SingleObserver<T> {
  private final SingleObserver<T> observer;
  private final AgentSpan span;

  public TracingSingleObserver(final SingleObserver<T> observer, final AgentSpan span) {
    this.observer = observer;
    this.span = span;
  }

  @Override
  public void onSubscribe(final Disposable d) {
    observer.onSubscribe(d);
  }

  @Override
  public void onSuccess(final T value) {
    try (final TraceScope scope = AgentTracer.activateSpan(span)) {
      observer.onSuccess(value);
    }
  }

  @Override
  public void onError(final Throwable e) {
    try (final TraceScope scope = AgentTracer.activateSpan(span)) {
      observer.onError(e);
    }
  }
}
