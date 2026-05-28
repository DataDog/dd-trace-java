package datadog.trace.instrumentation.jmh;

import javax.annotation.Nullable;

public final class JmhUtils {

  static final String FRAMEWORK_NAME = "jmh";

  /**
   * Splits a JMH benchmark name into suite (class) and method parts.
   *
   * <p>JMH names have the form {@code "com.example.MyBenchmark.myMethod"} or, when {@code @Param}
   * combinations are present, {@code "com.example.MyBenchmark.myMethod:size=1000,threads=4"}.
   */
  public static String[] splitBenchmarkName(String fullName) {
    // Strip any @Param suffix before splitting on the class/method boundary
    int colonIdx = fullName.indexOf(':');
    String baseName = colonIdx >= 0 ? fullName.substring(0, colonIdx) : fullName;

    int lastDot = baseName.lastIndexOf('.');
    if (lastDot < 0) {
      return new String[] {"", fullName};
    }
    return new String[] {baseName.substring(0, lastDot), fullName.substring(lastDot + 1)};
  }

  /**
   * Returns the {@code test.parameters} JSON string for a parameterized benchmark, or {@code null}
   * for an unparameterized one.
   *
   * <p>Follows the same convention as JUnit 5 parameterized tests: {@code
   * {"metadata":{"test_name":"<displayName>"}}}.
   */
  @Nullable
  public static String testParameters(String fullName) {
    int colonIdx = fullName.indexOf(':');
    if (colonIdx < 0) {
      return null;
    }
    // fullName after last dot includes the param suffix, e.g. "myMethod:size=1000"
    int lastDot = fullName.lastIndexOf('.', colonIdx);
    String displayName = lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    return "{\"metadata\":{\"test_name\":\"" + escapeJson(displayName) + "\"}}";
  }

  /** Minimal JSON string escaping for benchmark names (no unicode escaping needed). */
  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private JmhUtils() {}
}
