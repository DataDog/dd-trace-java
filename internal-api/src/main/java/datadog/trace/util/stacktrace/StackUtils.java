package datadog.trace.util.stacktrace;

import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Predicate;

public abstract class StackUtils {

  public static <E extends Throwable> E update(
      final E exception, final Function<StackTraceElement[], StackTraceElement[]> filter) {
    final StackTraceElement[] stack = exception.getStackTrace();
    exception.setStackTrace(filter.apply(stack));
    return exception;
  }

  public static <E extends Throwable> E filter(
      final E exception, final Predicate<StackTraceElement> filter) {
    return update(
        exception, stack -> Arrays.stream(stack).filter(filter).toArray(StackTraceElement[]::new));
  }

  public static <E extends Throwable> E filterFirst(
      final E exception, final Predicate<StackTraceElement> filter) {
    return filter(exception, new OneTimePredicate<>(filter));
  }

  public static <E extends Throwable> E filterDatadog(final E exception) {
    return filter(exception, AbstractStackWalker::isNotDatadogTraceStackElement);
  }

  public static <E extends Throwable> E filterFirstDatadog(final E exception) {
    return filterFirst(exception, AbstractStackWalker::isNotDatadogTraceStackElement);
  }

  public static <E extends Throwable> E filterUntil(
      final E exception, final Predicate<StackTraceElement> trace) {
    return update(
        exception,
        stack -> {
          final StackTraceElement[] source = exception.getStackTrace();
          for (int i = 0; i < source.length; i++) {
            if (trace.test(source[i])) {
              final StackTraceElement[] result = new StackTraceElement[source.length - i - 1];
              System.arraycopy(source, i + 1, result, 0, result.length);
              return result;
            }
          }
          return source;
        });
  }

  private static class OneTimePredicate<T> implements Predicate<T> {

    private final Predicate<T> delegate;
    private boolean filtered;

    private OneTimePredicate(final Predicate<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean test(final T item) {
      if (filtered) {
        return true;
      }
      final boolean test = delegate.test(item);
      if (!test) {
        filtered = true;
      }
      return test;
    }
  }
}
