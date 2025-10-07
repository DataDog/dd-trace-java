package datadog.environment;

import java.util.ArrayList;
import java.util.List;

/**
 * This class represents a Java version according the String Naming Convention.
 *
 * @see <a href="https://www.oracle.com/java/technologies/javase/versioning-naming.html">String
 *     Naming Convention</a>
 */
final class JavaVersion {
  final int major;
  final int minor;
  final int update;

  JavaVersion(int major, int minor, int update) {
    this.major = major;
    this.minor = minor;
    this.update = update;
  }

  static JavaVersion getRuntimeVersion() {
    return parseJavaVersion(SystemProperties.getOrDefault("java.version", ""));
  }

  static JavaVersion parseJavaVersion(String javaVersion) {
    // Remove pre-release part, usually -ea
    final int indexOfDash = javaVersion.indexOf('-');
    if (indexOfDash >= 0) {
      javaVersion = javaVersion.substring(0, indexOfDash);
    }

    int major = 0;
    int minor = 0;
    int update = 0;

    try {
      List<Integer> nums = splitDigits(javaVersion);
      major = nums.get(0);

      // for java 1.6/1.7/1.8
      if (major == 1) {
        major = nums.get(1);
        minor = nums.get(2);
        update = nums.get(3);
      } else {
        minor = nums.get(1);
        update = nums.get(2);
      }
    } catch (NumberFormatException | IndexOutOfBoundsException e) {
      // unable to parse version string - do nothing
    }
    return new JavaVersion(major, minor, update);
  }

  /* The method splits java version string by digits. Delimiters are: dot, underscore and plus */
  private static List<Integer> splitDigits(String str) {
    List<Integer> results = new ArrayList<>();

    int len = str.length();
    int value = 0;
    for (int i = 0; i < len; i++) {
      char ch = str.charAt(i);
      if (ch >= '0' && ch <= '9') {
        value = value * 10 + (ch - '0');
      } else if (ch == '.' || ch == '_' || ch == '+') {
        results.add(value);
        value = 0;
      } else {
        throw new NumberFormatException();
      }
    }
    results.add(value);
    return results;
  }

  public boolean is(int major) {
    return this.major == major;
  }

  public boolean is(int major, int minor) {
    return this.major == major && this.minor == minor;
  }

  public boolean is(int major, int minor, int update) {
    return this.major == major && this.minor == minor && this.update == update;
  }

  public boolean isAtLeast(int major) {
    return isAtLeast(major, 0, 0);
  }

  public boolean isAtLeast(int major, int minor, int update) {
    return isAtLeast(this.major, this.minor, this.update, major, minor, update);
  }

  public boolean isBetween(
      int fromMajor, int fromMinor, int fromUpdate, int toMajor, int toMinor, int toUpdate) {
    return isAtLeast(toMajor, toMinor, toUpdate, fromMajor, fromMinor, fromUpdate)
        && isAtLeast(fromMajor, fromMinor, fromUpdate)
        && !isAtLeast(toMajor, toMinor, toUpdate);
  }

  private static boolean isAtLeast(
      int major, int minor, int update, int atLeastMajor, int atLeastMinor, int atLeastUpdate) {
    return (major > atLeastMajor)
        || (major == atLeastMajor && minor > atLeastMinor)
        || (major == atLeastMajor && minor == atLeastMinor && update >= atLeastUpdate);
  }
}
