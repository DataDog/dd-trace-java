package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;

/**
 * This is used to wrap lambda runnables so we can apply field-injection. RunnableWrapper can be
 * transformed to add the necessary context-store fields, while lambdas currently cannot until the
 * issue reported in https://github.com/raphw/byte-buddy/issues/558 is addressed.
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
    if (!(task instanceof RunnableWrapper) && !ExcludeFilter.exclude(ExcludeType.RUNNABLE, task)) {
      // We wrap only lambdas' anonymous classes and if given object has not already been wrapped.
      // Anonymous classes have '/' in class name which is not allowed in 'normal' classes.
      final String className = task.getClass().getName();
      if (className.indexOf('/', className.lastIndexOf('.')) > 0) {
        return new RunnableWrapper(task);
      }
    }
    return task;
  }
}
