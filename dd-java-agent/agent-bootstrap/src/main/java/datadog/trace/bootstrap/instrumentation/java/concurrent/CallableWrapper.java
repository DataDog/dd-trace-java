package datadog.trace.bootstrap.instrumentation.java.concurrent;

import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;

/**
 * This is used to wrap lambda callables since currently we cannot instrument them
 *
 * <p>FIXME: We should remove this once https://github.com/raphw/byte-buddy/issues/558 is fixed
 */
@Slf4j
public final class CallableWrapper implements Callable {
  private final Callable callable;

  public CallableWrapper(final Callable callable) {
    this.callable = callable;
  }

  @Override
  public Object call() throws Exception {
    return callable.call();
  }

  public static Callable<?> wrapIfNeeded(final Callable<?> task) {
    if (!(task instanceof CallableWrapper) && !SelfContained.skip(task)) {
      // We wrap only lambdas' anonymous classes and if given object has not already been wrapped.
      // Anonymous classes have '/' in class name which is not allowed in 'normal' classes.
      final String className = task.getClass().getName();
      if (className.indexOf('/', className.lastIndexOf('.')) > 0) {
        return new CallableWrapper(task);
      }
    }
    return task;
  }
}
