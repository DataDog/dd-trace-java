package datadog.gradle.plugin.muzzle

import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.version.Version
import java.util.Locale

internal object MuzzleVersionUtils {
  private val END_NMN_PATTERN = Regex("^.*\\.[0-9]+[mM][0-9]+$")
  private val GIT_SHA_PATTERN = Regex("^.*-[0-9a-f]{7,}$")

  /**
   * Filter and limit the set of versions for muzzle testing.
   *
   * @param result The resolved version range result.
   * @param skipVersions Set of versions to skip.
   * @param includeSnapshots Whether to include snapshot versions.
   * @return A limited set of filtered versions for testing.
   */
  fun filterAndLimitVersions(
    result: VersionRangeResult,
    skipVersions: Set<String>,
    includeSnapshots: Boolean
  ): Set<Version> {
    val filtered = filterVersion(result.versions.toSet(), skipVersions, includeSnapshots)
    return limitLargeRanges(result, filtered, skipVersions)
  }

  /**
   * Filter out snapshot-type builds from versions list.
   *
   * @param list Set of versions to filter.
   * @param skipVersions Set of versions to skip.
   * @param includeSnapshots Whether to include snapshot versions.
   * @return Filtered set of versions.
   */
  private fun filterVersion(
    list: Set<Version>,
    skipVersions: Set<String>,
    includeSnapshots: Boolean
  ): Set<Version> = list.filter { version ->
    val v = version.toString().lowercase(Locale.ROOT)
    if (includeSnapshots) {
      !skipVersions.contains(v)
    } else {
      !(
        v.endsWith("-snapshot") ||
          v.contains("rc") ||
          v.contains(".cr") ||
          v.contains("alpha") ||
          v.contains("beta") ||
          v.contains("-b") ||
          v.contains(".m") ||
          v.contains("-m") ||
          v.contains("-dev") ||
          v.contains("-ea") ||
          v.contains("-atlassian-") ||
          v.contains("public_draft") ||
          v.contains("-cr") ||
          v.contains("-preview") ||
          skipVersions.contains(v) ||
          END_NMN_PATTERN.matches(v) ||
          GIT_SHA_PATTERN.matches(v)
        )
    }
  }.toSet()

  /**
   * Select a random set of versions to test
   */
  private val RANGE_COUNT_LIMIT = 25

  /**
   * Select a random set of versions to test, limiting the range for efficiency.
   *
   * @param result The resolved version range result.
   * @param versions The set of versions to consider.
   * @param skipVersions Set of versions to skip.
   * @return A limited set of versions for testing.
   */
  private fun limitLargeRanges(
    result: VersionRangeResult,
    versions: Set<Version>,
    skipVersions: Set<String>
  ): Set<Version> {
    if (versions.size <= 1) return versions
    val beforeSize = versions.size
    val filteredVersions = versions.toMutableList().apply {
      removeAll { skipVersions.contains(it.toString()) }
    }
    val versionSet = VersionSet(filteredVersions)
    val shuffled = versionSet.lowAndHighForMajorMinor.shuffled().toMutableList()
    var afterSize = shuffled.size
    while (RANGE_COUNT_LIMIT <= afterSize) {
      val version = shuffled.removeAt(0)
      if (version == result.lowestVersion || version == result.highestVersion) {
        shuffled.add(version)
      } else {
        afterSize -= 1
      }
    }

    if (beforeSize - afterSize > 0) {
      println("Muzzle skipping ${beforeSize - afterSize} versions")
    }

    return shuffled.toSet()
  }
}
