package datadog.trace.test.util;

import datadog.environment.OperatingSystem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Normalizes values produced by the current platform for comparison with test fixtures. */
public final class PlatformTestUtils {
  private PlatformTestUtils() {}

  /** Converts Windows CRLF line endings to LF; returns the original value on other platforms. */
  public static String normalizeLineEndings(String value) {
    return normalizeLineEndings(value, OperatingSystem.isWindows());
  }

  static String normalizeLineEndings(String value, boolean isWindows) {
    return isWindows ? value.replace("\r\n", "\n") : value;
  }

  /** Converts Windows path separators to slashes; returns the original value on other platforms. */
  public static String normalizePathSeparators(String value) {
    return normalizePathSeparators(value, OperatingSystem.isWindows());
  }

  static String normalizePathSeparators(String value, boolean isWindows) {
    return isWindows ? value.replace('\\', '/') : value;
  }

  /** Normalizes a collection of paths; returns the original collection on non-Windows platforms. */
  public static Collection<String> normalizePathSeparators(Collection<String> values) {
    return normalizePathSeparators(values, OperatingSystem.isWindows());
  }

  static Collection<String> normalizePathSeparators(Collection<String> values, boolean isWindows) {
    if (!isWindows) {
      return values;
    }
    List<String> normalizedValues = new ArrayList<>(values.size());
    for (String value : values) {
      normalizedValues.add(normalizePathSeparators(value, true));
    }
    return normalizedValues;
  }
}
