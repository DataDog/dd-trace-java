package datadog.communication.ddagent;

public class AgentVersion {

  /**
   * Checks if the given version string represents a version that is at least the specified major,
   * minor, and patch version.
   *
   * @param version the version string to check (e.g., "7.65.0")
   * @param minMajor minimum major version
   * @param minMinor minimum minor version
   * @param minPatch minimum patch version
   * @return true if version is at least the specified minimum, false otherwise (including when
   *     version is null or unparseable)
   */
  public static boolean isVersionAtLeast(String version, int minMajor, int minMinor, int minPatch) {
    if (version == null || version.isEmpty()) {
      return false;
    }

    try {
      // Parse version string in format "major.minor.patch" (e.g., "7.65.0")
      int majorDot = version.indexOf('.');
      if (majorDot == -1) {
        return false;
      }

      int major = Integer.parseInt(version.substring(0, majorDot));

      if (major > minMajor) {
        return true;
      } else if (major < minMajor) {
        return false;
      }

      // major == minMajor
      int minorDot = version.indexOf('.', majorDot + 1);
      if (minorDot == -1) {
        return false;
      }

      int minor = Integer.parseInt(version.substring(majorDot + 1, minorDot));
      if (minor > minMinor) {
        return true;
      } else if (minor < minMinor) {
        return false;
      }

      // major == minMajor && minor == minMinor
      // Find end of patch version (may have suffix like "-rc.1")
      int patchEnd = minorDot + 1;
      while (patchEnd < version.length() && Character.isDigit(version.charAt(patchEnd))) {
        patchEnd++;
      }

      int patch = Integer.parseInt(version.substring(minorDot + 1, patchEnd));
      return patch >= minPatch;
    } catch (NumberFormatException | IndexOutOfBoundsException e) {
      return false;
    }
  }
}
