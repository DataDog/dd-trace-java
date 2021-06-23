package datadog.trace.api;

import datadog.trace.util.Strings;

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

  private static Version parseJavaVersion(String javaVersion) {
    javaVersion = Strings.replace(javaVersion, "-ea", "");
    int major, minor, update;
    int firstDot = javaVersion.indexOf('.');
    int secondDot = javaVersion.indexOf('.', firstDot + 1);
    int underscore = javaVersion.indexOf('_', secondDot + 1);
    try {
      if (javaVersion.startsWith("1.")) {
        major =
            Integer.parseInt(
                javaVersion.substring(
                    firstDot + 1, secondDot < 0 ? javaVersion.length() : secondDot));
        minor =
            secondDot < 0
                ? 0
                : Integer.parseInt(
                    javaVersion.substring(
                        secondDot + 1, underscore < 0 ? javaVersion.length() : underscore));
        update =
            underscore < 0
                ? 0
                : Integer.parseInt(javaVersion.substring(underscore + 1, javaVersion.length()));
      } else {
        major =
            Integer.parseInt(
                javaVersion.substring(0, firstDot < 0 ? javaVersion.length() : firstDot));
        minor =
            firstDot < 0
                ? 0
                : Integer.parseInt(
                    javaVersion.substring(
                        firstDot + 1, secondDot < 0 ? javaVersion.length() : secondDot));
        update =
            secondDot < 0
                ? 0
                : Integer.parseInt(javaVersion.substring(secondDot + 1, javaVersion.length()));
      }
    } catch (NumberFormatException | IndexOutOfBoundsException e) {
      major = minor = update = 0;
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
}
