package datadog.trace.util.stacktrace;

import static com.google.common.truth.Truth.assertThat;

import java.util.function.Function;
import org.junit.jupiter.api.Test;

public class StackUtilsTest {

  @Test
  public void test_identity_function() {
    final Throwable source = new RuntimeException();
    final StackTraceElement[] expected = source.getStackTrace();

    final Throwable updated = StackUtils.update(withStack(expected), Function.identity());
    assertThat(updated.getStackTrace()).isEqualTo(expected);
  }

  @Test
  public void test_filter_all_datadog() {
    final StackTraceElement[] stack =
        new StackTraceElement[] {
          stack().className("org.junit.jupiter.api.Test").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtilsTest").build(),
          stack().className("java.util.function.Function").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtils").build(),
          stack().className("com.google.common.truth.Truth").build()
        };
    final StackTraceElement[] expected = new StackTraceElement[] {stack[0], stack[2], stack[4]};

    final Throwable filtered =
        StackUtils.filter(
            withStack(stack), item -> !item.getClassName().startsWith("datadog.trace"));
    assertThat(filtered.getStackTrace()).isEqualTo(expected);

    final Throwable filtered2 = StackUtils.filterDatadog(withStack(stack));
    assertThat(filtered2.getStackTrace()).isEqualTo(expected);
  }

  @Test
  public void test_stack_filters() {
    final StackTraceElement[] stack =
        new StackTraceElement[] {
          stack().className("org.junit.jupiter.api.Test").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtilsTest").build(),
          stack().className("java.util.function.Function").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtils").build(),
          stack().className("com.google.common.truth.Truth").build()
        };
    final StackTraceElement[] expected =
        new StackTraceElement[] {stack[0], stack[2], stack[3], stack[4]};

    final Throwable filtered =
        StackUtils.filterFirst(
            withStack(stack), item -> !item.getClassName().startsWith("datadog.trace"));
    assertThat(filtered.getStackTrace()).isEqualTo(expected);

    final Throwable filtered2 = StackUtils.filterFirstDatadog(withStack(stack));
    assertThat(filtered2.getStackTrace()).isEqualTo(expected);
  }

  @Test
  public void test_filter_until() {
    final StackTraceElement[] stack =
        new StackTraceElement[] {
          stack().className("org.junit.jupiter.api.Test").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtilsTest").build(),
          stack().className("java.util.function.Function").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtils").build(),
          stack().className("com.google.common.truth.Truth").build()
        };

    final StackTraceElement[] expected = new StackTraceElement[] {stack[4]};
    final Throwable removed =
        StackUtils.filterUntil(
            withStack(stack),
            entry -> entry.getClassName().equals("datadog.trace.util.stacktrace.StackUtils"));
    assertThat(removed.getStackTrace()).isEqualTo(expected);

    final Throwable noRemoval = StackUtils.filterUntil(withStack(stack), entry -> false);
    assertThat(noRemoval.getStackTrace()).isEqualTo(stack);
  }

  private static Throwable withStack(final StackTraceElement... stack) {
    final Exception exception = new RuntimeException();
    exception.setStackTrace(stack);
    return exception;
  }

  private static StackTraceBuilder stack() {
    return new StackTraceBuilder();
  }

  private static class StackTraceBuilder {

    private String className = "mock";
    private String methodName = "mock";
    private String fileName = "mock";
    private int lineNumber;

    public StackTraceBuilder className(final String className) {
      this.className = className;
      return this;
    }

    public StackTraceBuilder methodName(final String methodName) {
      this.methodName = methodName;
      return this;
    }

    public StackTraceBuilder fileName(final String fileName) {
      this.fileName = fileName;
      return this;
    }

    public StackTraceBuilder lineNumber(final int lineNumber) {
      this.lineNumber = lineNumber;
      return this;
    }

    public StackTraceElement build() {
      return new StackTraceElement(className, methodName, fileName, lineNumber);
    }
  }
}
