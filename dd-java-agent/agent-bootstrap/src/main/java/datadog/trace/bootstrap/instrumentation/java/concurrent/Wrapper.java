package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.trace.context.TraceScope;
import java.util.concurrent.RunnableFuture;

public class Wrapper<T extends Runnable> implements Runnable, AutoCloseable {

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static <T extends Runnable> Runnable wrap(T task) {
    if (task instanceof Wrapper
        || task instanceof RunnableFuture
        || task == null
        || exclude(RUNNABLE, task)) {
      return task;
    }
    TraceScope scope = activeScope();
    if (null != scope) {
      if (task instanceof Comparable) {
        return new ComparableRunnable(task, scope.capture());
      }
      return new Wrapper<>(task, scope.capture());
    }
    // don't wrap unless there is scope to propagate
    return task;
  }

  protected final T delegate;
  private final TraceScope.Continuation continuation;

  public Wrapper(T delegate, TraceScope.Continuation continuation) {
    this.delegate = delegate;
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

  @Override
  public void close() throws Exception {
    cancel();
    if (delegate instanceof AutoCloseable) {
      ((AutoCloseable) delegate).close();
    }
  }
}
