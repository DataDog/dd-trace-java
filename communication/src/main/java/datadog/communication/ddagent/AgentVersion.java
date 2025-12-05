package datadog.communication.ddagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentVersion {

  private static final Logger log = LoggerFactory.getLogger(AgentVersion.class);

  /**
   * Checks if the given version string represents a version that is below the specified major,
   * minor, and patch version.
   *
   * @param version the version string to check (e.g., "7.64.0")
   * @param maxMajor maximum major version (exclusive)
   * @param maxMinor maximum minor version (exclusive)
   * @param maxPatch maximum patch version (exclusive)
   * @return true if version is below the specified maximum, false otherwise (including when
   *     version is null or unparseable)
   */
  public static boolean isVersionBelow(String version, int maxMajor, int maxMinor, int maxPatch) {
    if (version == null || version.isEmpty()) {
      return true;
    }

    try {
      // Parse version string in format "major.minor.patch" (e.g., "7.65.0")
      // Assumes the 'version' is below if it can't be parsed.
      int majorDot = version.indexOf('.');
      if (majorDot == -1) {
        return true;
      }

      int major = Integer.parseInt(version.substring(0, majorDot));

      if (major < maxMajor) {
        return true;
      } else if (major > maxMajor) {
        return false;
      }

      // major == maxMajor
      int minorDot = version.indexOf('.', majorDot + 1);
      if (minorDot == -1) {
        return true;
      }

      int minor = Integer.parseInt(version.substring(majorDot + 1, minorDot));
      if (minor < maxMinor) {
        return true;
      } else if (minor > maxMinor) {
        return false;
      }

      // major == maxMajor && minor == maxMinor
      // Find end of patch version (may have suffix like "-rc.1")
      int patchEnd = minorDot + 1;
      while (patchEnd < version.length() && Character.isDigit(version.charAt(patchEnd))) {
        patchEnd++;
      }

      int patch = Integer.parseInt(version.substring(minorDot + 1, patchEnd));
      if (patch != maxPatch) {
        return patch < maxPatch;
      } else {
        // If there's a suffix (like "-rc.1"), consider it below the non-suffixed version
        return patchEnd < version.length();
      }
    } catch (NumberFormatException | IndexOutOfBoundsException e) {
      return true;
    }
  }
}
