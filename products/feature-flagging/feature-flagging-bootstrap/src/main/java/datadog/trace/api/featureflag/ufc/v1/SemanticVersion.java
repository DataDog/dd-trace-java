package datadog.trace.api.featureflag.ufc.v1;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SemanticVersion implements Comparable<SemanticVersion> {
  private static final Pattern PATTERN =
      Pattern.compile(
          "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

  private final String major;
  private final String minor;
  private final String patch;
  private final String[] prerelease;

  private SemanticVersion(
      final String major, final String minor, final String patch, final String prerelease) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.prerelease = prerelease == null ? null : prerelease.split("\\.");
  }

  public static SemanticVersion parse(final Object value) {
    if (!(value instanceof String)) {
      throw new IllegalArgumentException("semantic version must be a string");
    }
    final Matcher matcher = PATTERN.matcher((String) value);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("invalid semantic version: " + value);
    }
    FlagValidator.validateSemanticVersionComponent(matcher.group(1));
    FlagValidator.validateSemanticVersionComponent(matcher.group(2));
    FlagValidator.validateSemanticVersionComponent(matcher.group(3));
    return new SemanticVersion(
        matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
  }

  @Override
  public int compareTo(final SemanticVersion other) {
    int result = compareNumeric(major, other.major);
    if (result == 0) {
      result = compareNumeric(minor, other.minor);
    }
    if (result == 0) {
      result = compareNumeric(patch, other.patch);
    }
    if (result != 0 || prerelease == null && other.prerelease == null) {
      return result;
    }
    if (prerelease == null) {
      return 1;
    }
    if (other.prerelease == null) {
      return -1;
    }
    final int count = Math.min(prerelease.length, other.prerelease.length);
    for (int index = 0; index < count; index++) {
      result = compareIdentifier(prerelease[index], other.prerelease[index]);
      if (result != 0) {
        return result;
      }
    }
    return Integer.compare(prerelease.length, other.prerelease.length);
  }

  private static int compareIdentifier(final String left, final String right) {
    final boolean leftNumeric = isNumeric(left);
    final boolean rightNumeric = isNumeric(right);
    if (leftNumeric && rightNumeric) {
      return compareNumeric(left, right);
    }
    if (leftNumeric != rightNumeric) {
      return leftNumeric ? -1 : 1;
    }
    return left.compareTo(right);
  }

  private static int compareNumeric(final String left, final String right) {
    final int length = Integer.compare(left.length(), right.length());
    return length == 0 ? left.compareTo(right) : length;
  }

  private static boolean isNumeric(final String value) {
    for (int index = 0; index < value.length(); index++) {
      if (!Character.isDigit(value.charAt(index))) {
        return false;
      }
    }
    return true;
  }
}
