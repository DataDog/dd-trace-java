package datadog.gradle.plugin.muzzle

import org.eclipse.aether.version.Version

import java.util.regex.Pattern

class VersionSet {
  private final SortedSet<ParsedVersion> sortedVersions

  VersionSet(Collection<Version> versions) {
    sortedVersions = new TreeSet<>()
    for (Version version : versions) {
      def parsed = new ParsedVersion(version)
      sortedVersions.add(parsed)
    }
  }

  List<Version> getLowAndHighForMajorMinor() {
    ParsedVersion previous = null
    int currentMajorMinor = -1
    def resultSet = new TreeSet<ParsedVersion>()
    for (ParsedVersion parsed: sortedVersions) {
      int majorMinor = parsed.majorMinor
      if (majorMinor != currentMajorMinor) {
        if (previous != null) {
          resultSet.add(previous)
          previous = null
        }
        resultSet.add(parsed)
        currentMajorMinor = majorMinor
      } else {
        previous = parsed
      }
    }
    if (previous != null) {
      resultSet.add(previous)
    }
    return resultSet.collect {it.version }
  }

  static class ParsedVersion implements Comparable<ParsedVersion> {
    private static final Pattern dotPattern = Pattern.compile("\\.")
    private static final int VERSION_SHIFT = 12

    private final Version version
    private final long versionNumber
    private final String ending

    ParsedVersion(Version version) {
      this.version = version
      def versionString = version.toString()
      def ending = ""
      // Remove any trailing parts from the version
      def dash = versionString.indexOf('-')
      if (dash > 0) {
        ending = versionString.substring(dash + 1)
        versionString = versionString.substring(0, dash)
      }
      def groups = dotPattern.split(versionString).toList()
      int versionNumber = 0
      int iteration = 0
      // We assume that there are no more than 3 version numbers
      while (iteration < 3) {
        versionNumber <<= VERSION_SHIFT
        if (!groups.empty && groups.head().isInteger()) {
          versionNumber += groups.pop().toInteger()
        }
        iteration++
      }
      if (!groups.empty) {
        def rest = groups.join('.')
        ending = ending.empty ? rest : "$rest-$ending"
      }
      this.versionNumber = versionNumber
      this.ending = ending
    }

    boolean equals(o) {
      if (this.is(o)) return true
      if (getClass() != o.class) return false
      ParsedVersion that = (ParsedVersion) o
      if (versionNumber != that.versionNumber) return false
      if (ending != that.ending) return false
      return true
    }

    int hashCode() {
      return versionNumber * 31 + ending.hashCode()
    }

    @Override
    int compareTo(ParsedVersion other) {
      def diff = versionNumber - other.versionNumber
      return diff != 0 ? diff : ending <=> other.ending
    }

    Version getVersion() {
      return version
    }

    long getVersionNumber() {
      return versionNumber
    }

    String getEnding() {
      return ending
    }

    int getMajorMinor() {
      return versionNumber >> VERSION_SHIFT
    }
  }
}
