package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;

/**
 * Wraps anonymous Runnable classes that were not field-injected.
 *
 * <p>We also make this class final to stop instrumentations from extending it in their injected
 * helper classes, because if this class is loaded during helper injection then we can miss the
 * initial load event where we need to add the context-store fields.
 */
public final class RunnableWrapper implements Runnable {

  private final Runnable runnable;

  public RunnableWrapper(final Runnable runnable) {
    this.runnable = runnable;
  }

  @Override
  public void run() {
    runnable.run();
  }

  public static Runnable wrapIfNeeded(final Runnable task) {
    // Field-injected tasks are already instrumented and must retain their identity.
    if (!(task instanceof RunnableWrapper)
        && !(task instanceof FieldBackedContextAccessor)
        && !ExcludeFilter.exclude(ExcludeType.RUNNABLE, task)) {
      // Hidden lambda class names contain '/'.
      final String className = task.getClass().getName();
      if (className.indexOf('/', className.lastIndexOf('.')) > 0) {
        return new RunnableWrapper(task);
      }
    }
    return task;
  }
}
