package datadog.trace.api;

public final class Platform {

  private static final int JAVA_MAJOR_VERSION = getJavaMajorVersion();

  public static boolean isJavaVersionAtLeast(int version) {
    return JAVA_MAJOR_VERSION >= version;
  }

  private static int getJavaMajorVersion() {
    return parseJavaVersion(System.getProperty("java.version"));
  }

  static int parseJavaVersion(String javaVersion) {
    javaVersion = javaVersion.replace("-ea", "");
    try {
      if (javaVersion.startsWith("1.")) {
        int secondDot = javaVersion.indexOf('.', 2);
        return Integer.parseInt(
            javaVersion.substring(2, secondDot < 0 ? javaVersion.length() : secondDot));
      } else {
        int firstDot = javaVersion.indexOf('.');
        if (firstDot > 0) {
          return Integer.parseInt(javaVersion.substring(0, firstDot));
        }
        return Integer.parseInt(javaVersion);
      }
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
