package datadog.trace.bootstrap.instrumentation.java.concurrent;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.RunnableFuture;

/** Advice placeholder representing a call to {@link AbstractExecutorService#newTaskFor}. */
public final class NewTaskForPlaceholder {
  // arguments chosen to match the real non-static call, except the first is the executor itself
  public static <T> RunnableFuture<T> newTaskFor(
      final Executor executor, final Runnable task, final T value) {
    throw new RuntimeException(
        "Calls to this method will be rewritten by NewTaskForRewritingVisitor");
  }
}
