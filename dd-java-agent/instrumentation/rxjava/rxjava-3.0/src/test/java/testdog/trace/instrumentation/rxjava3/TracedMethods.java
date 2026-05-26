package testdog.trace.instrumentation.rxjava3;

import datadog.trace.api.Trace;

/**
 * Helper class with {@link Trace}-annotated methods, kept in the {@code testdog} package to avoid
 * the global ignore rule for {@code datadog.trace.*} classes, allowing the agent to instrument them
 * at class-load time.
 */
public final class TracedMethods {

  @Trace(operationName = "addOne", resourceName = "addOne")
  public static int addOneFunc(int i) {
    return i + 1;
  }

  @Trace(operationName = "addTwo", resourceName = "addTwo")
  public static int addTwoFunc(int i) {
    return i + 2;
  }

  private TracedMethods() {}
}
