package com.datadog.appsec.sca;

import datadog.trace.util.ComparableVersion;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks whether a version string matches GHSA version range expressions.
 *
 * <p>Range string format (per sca-reachability-database enrichments):
 *
 * <ul>
 *   <li>{@code "< 2.6.7.3"} - strictly less than
 *   <li>{@code "<= 2.6.7.3"} - less than or equal
 *   <li>{@code "> 2.6.7.3"} - strictly greater than
 *   <li>{@code ">= 2.6.7.3"} - greater than or equal
 *   <li>{@code "= 2.6.7.3"} - exact match
 *   <li>{@code ">= 2.7.0, < 2.7.9.5"} - AND of two conditions (comma-separated)
 * </ul>
 *
 * <p>Multiple range strings in a list are evaluated as OR: a version is affected if it matches ANY
 * of the ranges.
 *
 * <p>Version comparison uses {@link ComparableVersion} (Maven 3.9.9 semantics), which correctly
 * handles qualifiers such as {@code .RELEASE}, {@code .GA}, {@code .FINAL}, and 4-part versions.
 */
public final class VersionRangeParser {

  private static final Pattern COMMA = Pattern.compile(",");

  private VersionRangeParser() {}

  /**
   * Returns true if {@code version} matches at least one of the provided range strings.
   *
   * @param version the version to test (e.g. {@code "2.8.5"} or {@code "5.2.19.RELEASE"})
   * @param versionRanges list of range strings from sca_cves.json
   * @return true if the version falls within any range
   */
  public static boolean matchesAny(String version, List<String> versionRanges) {
    if (version == null || version.isEmpty() || versionRanges == null || versionRanges.isEmpty()) {
      return false;
    }
    ComparableVersion v = new ComparableVersion(version);
    for (String range : versionRanges) {
      if (matchesRange(v, range)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns true if {@code version} matches a single range string. Multiple conditions within a
   * single string (comma-separated) are evaluated as AND.
   */
  static boolean matchesRange(ComparableVersion version, String versionRange) {
    String[] conditions = COMMA.split(versionRange);
    for (String condition : conditions) {
      if (!matchesCondition(version, condition.trim())) {
        return false;
      }
    }
    return true;
  }

  private static boolean matchesCondition(ComparableVersion version, String condition) {
    if (condition.startsWith(">=")) {
      return version.compareTo(new ComparableVersion(condition.substring(2).trim())) >= 0;
    }
    if (condition.startsWith("<=")) {
      return version.compareTo(new ComparableVersion(condition.substring(2).trim())) <= 0;
    }
    if (condition.startsWith(">")) {
      return version.compareTo(new ComparableVersion(condition.substring(1).trim())) > 0;
    }
    if (condition.startsWith("<")) {
      return version.compareTo(new ComparableVersion(condition.substring(1).trim())) < 0;
    }
    if (condition.startsWith("=")) {
      return version.compareTo(new ComparableVersion(condition.substring(1).trim())) == 0;
    }
    throw new IllegalArgumentException("Unrecognised version condition: '" + condition + "'");
  }
}
