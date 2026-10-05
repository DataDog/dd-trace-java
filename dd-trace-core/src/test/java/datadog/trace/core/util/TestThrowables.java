package datadog.trace.core.util;

import java.text.MessageFormat;

/** Test helpers for throwables with non-standard {@code getMessage()} behaviour. */
public final class TestThrowables {
  private TestThrowables() {}

  /**
   * Returns a {@link RuntimeException} whose {@link Throwable#getMessage()} throws {@link
   * IllegalArgumentException} via {@link MessageFormat} with non-integer placeholders — simulating
   * the third-party exception that triggered the production bug in {@code DDSpan.addThrowable}.
   */
  public static RuntimeException throwingGetMessage() {
    return new RuntimeException() {
      @Override
      public String getMessage() {
        return MessageFormat.format(
            "Timeout after {TotalMilliseconds}ms matching pattern {Pattern}", "arg0", "arg1");
      }
    };
  }

  /**
   * Returns a {@link RuntimeException} whose {@link Throwable#printStackTrace(java.io.PrintWriter)}
   * throws a {@link StackOverflowError} — simulating a second overflow while formatting a throwable
   * that was itself caught with little remaining stack margin.
   */
  public static RuntimeException throwingStackOverflowOnPrintStackTrace() {
    return new RuntimeException() {
      @Override
      public void printStackTrace(java.io.PrintWriter s) {
        throw new StackOverflowError();
      }
    };
  }

  /**
   * Returns a {@link RuntimeException} whose {@link Throwable#printStackTrace(java.io.PrintWriter)}
   * and {@link Throwable#getStackTrace()} both throw {@link StackOverflowError} — simulating a
   * throwable caught with essentially no remaining stack margin, where even the array-based
   * fallback in {@link StackTraces#getStackTrace} fails and only {@link Throwable#getMessage()}
   * remains callable.
   */
  public static RuntimeException throwingStackOverflowEverywhereExceptGetMessage() {
    return new RuntimeException("still readable") {
      @Override
      public void printStackTrace(java.io.PrintWriter s) {
        throw new StackOverflowError();
      }

      @Override
      public StackTraceElement[] getStackTrace() {
        throw new StackOverflowError();
      }
    };
  }

  /**
   * Returns a {@link RuntimeException} whose {@link
   * Throwable#printStackTrace(java.io.PrintWriter)}, {@link Throwable#getStackTrace()}, and {@link
   * Throwable#getMessage()} all throw {@link StackOverflowError} — the worst case, where {@link
   * StackTraces#getStackTrace} must fall back to just the throwable's class name.
   */
  public static RuntimeException throwingStackOverflowEverywhere() {
    return new RuntimeException() {
      @Override
      public void printStackTrace(java.io.PrintWriter s) {
        throw new StackOverflowError();
      }

      @Override
      public StackTraceElement[] getStackTrace() {
        throw new StackOverflowError();
      }

      @Override
      public String getMessage() {
        throw new StackOverflowError();
      }
    };
  }
}
