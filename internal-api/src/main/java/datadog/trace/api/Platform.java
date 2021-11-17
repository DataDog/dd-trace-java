package datadog.trace.api;

import datadog.trace.util.Strings;
import java.util.LinkedList;
import java.util.List;

public final class Platform {

  private static final int JAVA_MAJOR_VERSION;
  private static final int JAVA_MINOR_VERSION;
  private static final int JAVA_UPDATE_VERSION;

  static {
    Version version = parseJavaVersion(System.getProperty("java.version"));
    JAVA_MAJOR_VERSION = version.major;
    JAVA_MINOR_VERSION = version.minor;
    JAVA_UPDATE_VERSION = version.update;
  }

  /* The method splits java version string by digits. Delimiters are: dot, underscore and plus */
  private static List<Integer> splitDigits(String str) {
    List<Integer> results = new LinkedList<>();

    byte[] arr = str.getBytes();
    int len = str.length();

    int value = 0;
    for (int i = 0; i < len; i++) {
      byte ch = arr[i];
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

  private static class Version {
    public final int major, minor, update;

    public Version(int major, int minor, int update) {
      this.major = major;
      this.minor = minor;
      this.update = update;
    }
  }

  public static boolean isJavaVersion(int major) {
    return JAVA_MAJOR_VERSION == major;
  }

  public static boolean isJavaVersion(int major, int minor) {
    return JAVA_MAJOR_VERSION == major && JAVA_MINOR_VERSION == minor;
  }

  public static boolean isJavaVersion(int major, int minor, int update) {
    return JAVA_MAJOR_VERSION == major
        && JAVA_MINOR_VERSION == minor
        && JAVA_UPDATE_VERSION == update;
  }

  public static boolean isJavaVersionAtLeast(int major) {
    return isJavaVersionAtLeast(major, 0, 0);
  }

  public static boolean isJavaVersionAtLeast(int major, int minor) {
    return isJavaVersionAtLeast(major, minor, 0);
  }

  public static boolean isJavaVersionAtLeast(int major, int minor, int update) {
    return (JAVA_MAJOR_VERSION > major)
        || (JAVA_MAJOR_VERSION == major && JAVA_MINOR_VERSION > minor)
        || (JAVA_MAJOR_VERSION == major
            && JAVA_MINOR_VERSION == minor
            && JAVA_UPDATE_VERSION >= update);
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
}
