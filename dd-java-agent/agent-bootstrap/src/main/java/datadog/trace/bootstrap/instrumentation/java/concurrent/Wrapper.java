package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
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
    ContextContinuation continuation = captureActiveSpan();
    if (continuation.context() != Context.root()) {
      if (task instanceof Comparable) {
        return new ComparableRunnable(task, continuation);
      }
      return new Wrapper<>(task, continuation);
    }
    // don't wrap unless there is scope to propagate
    return task;
  }

  public static Runnable unwrap(Runnable task) {
    return task instanceof Wrapper ? ((Wrapper<?>) task).unwrap() : task;
  }

  protected final T delegate;
  private final ContextContinuation continuation;

  public Wrapper(T delegate, ContextContinuation continuation) {
    this.delegate = delegate;
    this.continuation = continuation;
  }

  @Override
  public void run() {
    try (ContextScope scope = activate()) {
      delegate.run();
    }
  }

  public void cancel() {
    if (null != continuation) {
      continuation.release();
    }
  }

  public T unwrap() {
    return delegate;
  }

  private ContextScope activate() {
    return null == continuation ? null : continuation.resume();
  }

  @Override
  public void close() throws Exception {
    cancel();
    if (delegate instanceof AutoCloseable) {
      ((AutoCloseable) delegate).close();
    }
  }
}
