package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import datadog.trace.context.TraceScope;

public final class ComparableRunnable<T extends Runnable & Comparable<T>>
    implements Runnable, Comparable<ComparableRunnable<T>> {

  private final T delegate;
  private final TraceScope.Continuation continuation;

  public ComparableRunnable(T delegate) {
    this.delegate = delegate;
    TraceScope.Continuation continuation = null;
    TraceScope scope = activeScope();
    if (null != scope) {
      continuation = scope.capture();
    }
    this.continuation = continuation;
  }

  @Override
  public int compareTo(ComparableRunnable<T> o) {
    return delegate.compareTo(o.delegate);
  }

  @Override
  public void run() {
    try (TraceScope scope = activate()) {
      delegate.run();
    }
  }

  private TraceScope activate() {
    return null == continuation ? null : continuation.activate();
  }
}
