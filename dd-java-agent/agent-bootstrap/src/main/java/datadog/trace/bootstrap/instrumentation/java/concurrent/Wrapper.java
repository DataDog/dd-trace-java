package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import datadog.trace.context.TraceScope;

public class Wrapper<T extends Runnable> implements Runnable {

  protected final T delegate;
  private final TraceScope.Continuation continuation;

  public Wrapper(T delegate) {
    this.delegate = delegate;
    TraceScope.Continuation continuation = null;
    TraceScope scope = activeScope();
    if (null != scope) {
      continuation = scope.capture();
    }
    this.continuation = continuation;
  }

  @Override
  public void run() {
    try (TraceScope scope = activate()) {
      delegate.run();
    }
  }

  public void cancel() {
    if (null != continuation) {
      continuation.cancel();
    }
  }

  public T unwrap() {
    return delegate;
  }

  private TraceScope activate() {
    return null == continuation ? null : continuation.activate();
  }
}
