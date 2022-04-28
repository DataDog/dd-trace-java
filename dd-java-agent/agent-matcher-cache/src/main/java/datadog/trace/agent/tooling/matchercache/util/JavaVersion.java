package datadog.trace.agent.tooling.matchercache.util;

public class JavaVersion {
  public static final int MAJOR_VERSION = getJavaMajorVersion();

  private static int getJavaMajorVersion() {
    String fullVersion = System.getProperty("java.version");
    String majorVersionStr = null;
    int majorVersion = 0;
    if (fullVersion.startsWith("1.")) {
      majorVersionStr = fullVersion.substring(2, 3);
    } else {
      int versionStartsAt = fullVersion.indexOf(".");
      if (versionStartsAt >= 0) {
        majorVersionStr = fullVersion.substring(0, versionStartsAt);
      }
    }
    try {
      if (majorVersionStr != null) {
        majorVersion = Integer.parseInt(majorVersionStr);
      }
    } catch (NumberFormatException e) {
      // ignore
    }
    if (majorVersion == 0) {
      throw new IllegalStateException("Couldn't parse Java major version: " + fullVersion);
    }
    return majorVersion;
  }
}
