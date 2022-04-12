package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
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
    AgentScope scope = activeScope();
    if (null != scope) {
      if (task instanceof Comparable) {
        return new ComparableRunnable(task, scope.capture());
      }
      return new Wrapper<>(task, scope.capture());
    }
    // don't wrap unless there is scope to propagate
    return task;
  }

  public static Runnable unwrap(Runnable task) {
    return task instanceof Wrapper ? ((Wrapper<?>) task).unwrap() : task;
  }

  protected final T delegate;
  private final AgentScope.Continuation continuation;

  public Wrapper(T delegate, AgentScope.Continuation continuation) {
    this.delegate = delegate;
    this.continuation = continuation;
    if (null != continuation) {
      continuation.migrate();
    }
  }

  @Override
  public void run() {
    try (AgentScope scope = activate()) {
      try {
        delegate.run();
      } finally {
        if (null != scope) {
          scope.span().finishWork();
        }
      }
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

  private AgentScope activate() {
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
