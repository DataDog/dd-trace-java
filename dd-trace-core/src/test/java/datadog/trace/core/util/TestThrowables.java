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
}
