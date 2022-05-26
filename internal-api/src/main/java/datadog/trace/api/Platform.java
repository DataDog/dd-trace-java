package datadog.trace.api;

import datadog.trace.util.Strings;
import java.util.ArrayList;
import java.util.List;

public final class Platform {

  private static final Version JAVA_VERSION = parseJavaVersion(System.getProperty("java.version"));
  private static final JvmRuntime RUNTIME = new JvmRuntime();

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

  private static Version parseJavaVersion(String javaVersion) {
    javaVersion = Strings.replace(javaVersion, "-ea", "");

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
    return new Version(major, minor, update);
  }

  static final class Version {
    public final int major, minor, update;

    public Version(int major, int minor, int update) {
      this.major = major;
      this.minor = minor;
      this.update = update;
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

  static final class JvmRuntime {
    /*
     * Example:
     *    jvm     -> "AdoptOpenJDK 1.8.0_265-b01"
     *
     *    name    -> "OpenJDK"
     *    vendor  -> "AdoptOpenJDK"
     *    version -> "1.8.0_265"
     *    patches -> "b01"
     */
    public final String name;

    public final String vendor;
    public final String version;
    public final String patches;

    public JvmRuntime() {
      String rtVer = System.getProperty("java.runtime.version");
      String javaVer = System.getProperty("java.version");
      this.name = System.getProperty("java.runtime.name");
      this.vendor = System.getProperty("java.vm.vendor");
      this.version = javaVer;
      this.patches = rtVer.substring(javaVer.length() + 1);
    }
  }

  public static boolean isJavaVersion(int major) {
    return JAVA_VERSION.is(major);
  }

  public static boolean isJavaVersion(int major, int minor) {
    return JAVA_VERSION.is(major, minor);
  }

  public static boolean isJavaVersion(int major, int minor, int update) {
    return JAVA_VERSION.is(major, minor, update);
  }

  public static boolean isJavaVersionAtLeast(int major) {
    return isJavaVersionAtLeast(major, 0, 0);
  }

  public static boolean isJavaVersionAtLeast(int major, int minor) {
    return isJavaVersionAtLeast(major, minor, 0);
  }

  public static boolean isJavaVersionAtLeast(int major, int minor, int update) {
    return JAVA_VERSION.isAtLeast(major, minor, update);
  }

  /**
   * Check if the Java version is between {@code fromMajor} (inclusive) and {@code toMajor}
   * (exclusive).
   *
   * @param fromMajor major from version (inclusive)
   * @param toMajor major to version (exclusive)
   * @return if the current java version is between the from version (inclusive) and the to version
   *     exclusive
   */
  public static boolean isJavaVersionBetween(int fromMajor, int toMajor) {
    return isJavaVersionBetween(fromMajor, 0, toMajor, 0);
  }

  /**
   * Check if the Java version is between {@code fromMajor.fromMinor} (inclusive) and {@code
   * toMajor.toMinor} (exclusive).
   *
   * @param fromMajor major from version (inclusive)
   * @param fromMinor minor from version (inclusive)
   * @param toMajor major to version (exclusive)
   * @param toMinor minor to version (exclusive)
   * @return if the current java version is between the from version (inclusive) and the to version
   *     exclusive
   */
  public static boolean isJavaVersionBetween(
      int fromMajor, int fromMinor, int toMajor, int toMinor) {
    return isJavaVersionBetween(fromMajor, fromMinor, 0, toMajor, toMinor, 0);
  }

  /**
   * Check if the Java version is between {@code fromMajor.fromMinor.fromUpdate} (inclusive) and
   * {@code toMajor.toMinor.toUpdate} (exclusive).
   *
   * @param fromMajor major from version (inclusive)
   * @param fromMinor minor from version (inclusive)
   * @param fromUpdate update from version (inclusive)
   * @param toMajor major to version (exclusive)
   * @param toMinor minor to version (exclusive)
   * @param toUpdate update to version (exclusive)
   * @return if the current java version is between the from version (inclusive) and the to version
   *     exclusive
   */
  public static boolean isJavaVersionBetween(
      int fromMajor, int fromMinor, int fromUpdate, int toMajor, int toMinor, int toUpdate) {
    return JAVA_VERSION.isBetween(fromMajor, fromMinor, fromUpdate, toMajor, toMinor, toUpdate);
  }

  public static boolean isWindows() {
    // https://mkyong.com/java/how-to-detect-os-in-java-systemgetpropertyosname/
    final String os = System.getProperty("os.name").toLowerCase();
    return os.contains("win");
  }

  public static boolean isMac() {
    final String os = System.getProperty("os.name").toLowerCase();
    return os.contains("mac");
  }

  public static boolean isOracleJDK8() {
    return isJavaVersion(8)
        && RUNTIME.vendor.contains("Oracle")
        && !RUNTIME.name.contains("OpenJDK");
  }

  public static String getLangVersion() {
    return String.valueOf(JAVA_VERSION.major);
  }

  public static String getRuntimeVendor() {
    return RUNTIME.vendor;
  }

  public static String getRuntimeVersion() {
    return RUNTIME.version;
  }

  public static String getRuntimePatches() {
    return RUNTIME.patches;
  }
}
