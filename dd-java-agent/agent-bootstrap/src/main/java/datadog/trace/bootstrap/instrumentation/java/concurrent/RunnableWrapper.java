package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;

/**
 * This is used to wrap lambda runnables since currently we cannot instrument them
 *
 * <p>FIXME: We should remove this once https://github.com/raphw/byte-buddy/issues/558 is fixed
 */
public class RunnableWrapper implements Runnable {

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
