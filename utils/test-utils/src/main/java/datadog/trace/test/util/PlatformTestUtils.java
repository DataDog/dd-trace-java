package datadog.trace.test.util;

import datadog.environment.OperatingSystem;
import java.net.URI;
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

  /** Removes the Windows executable suffix; returns the original value on other platforms. */
  public static String normalizeExecutableName(String value) {
    return normalizeExecutableName(value, OperatingSystem.isWindows());
  }

  static String normalizeExecutableName(String value, boolean isWindows) {
    if (isWindows
        && value != null
        && value.length() > 4
        && value.regionMatches(true, value.length() - 4, ".exe", 0, 4)) {
      return value.substring(0, value.length() - 4);
    }
    return value;
  }

  /** Treats an exact IPv4 loopback hostname as localhost on Windows for test comparisons. */
  public static String normalizeLocalhostHostname(String value) {
    return normalizeLocalhostHostname(value, OperatingSystem.isWindows());
  }

  static String normalizeLocalhostHostname(String value, boolean isWindows) {
    return isWindows && "127.0.0.1".equals(value) ? "localhost" : value;
  }

  /** Treats an IPv4 loopback URL host as localhost on Windows, preserving the rest verbatim. */
  public static String normalizeLocalhostUrl(String value) {
    return normalizeLocalhostUrl(value, OperatingSystem.isWindows());
  }

  static String normalizeLocalhostUrl(String value, boolean isWindows) {
    if (!isWindows || value == null) {
      return value;
    }
    try {
      URI uri = URI.create(value);
      if (!"127.0.0.1".equals(uri.getHost())) {
        return value;
      }
      int authorityStart = value.indexOf("//") + 2;
      int hostStart =
          authorityStart + (uri.getRawUserInfo() == null ? 0 : uri.getRawUserInfo().length() + 1);
      return value.substring(0, hostStart)
          + "localhost"
          + value.substring(hostStart + "127.0.0.1".length());
    } catch (IllegalArgumentException ignored) {
      return value;
    }
  }
}
