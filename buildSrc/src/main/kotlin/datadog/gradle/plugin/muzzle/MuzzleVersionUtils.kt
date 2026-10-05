package datadog.gradle.plugin.muzzle

import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.version.Version
import java.util.Locale
import kotlin.random.Random

internal object MuzzleVersionUtils {
  private val END_NMN_PATTERN = Regex("^.*\\.[0-9]+[mM][0-9]+$")
  private val GIT_SHA_PATTERN = Regex("^.*-[0-9a-f]{7,}$")

  /**
   * Filter and limit the set of versions for muzzle testing.
   *
   * @param result The resolved version range result.
   * @param skipVersions Set of versions to skip.
   * @param includeSnapshots Whether to include snapshot versions.
   * @param isEligible Publication-age eligibility of sampled boundaries and their backfills.
   * @return A limited set of filtered versions for testing.
   */
  fun filterAndLimitVersions(
    result: VersionRangeResult,
    skipVersions: Set<String>,
    includeSnapshots: Boolean,
    random: Random = Random.Default,
    isEligible: (Version) -> Boolean = { true }
  ): Set<Version> {
    val filtered = filterVersion(result.versions.toSet(), skipVersions, includeSnapshots)
    return limitLargeRanges(filtered, isEligible, random)
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
  ): Set<Version> {
    return list.filter { version ->
      if (skipVersions.contains(version.toString())) return@filter false
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
            v.contains("redhat") || // redhat releases often cause ArtifactNotFoundException
            skipVersions.contains(v) ||
            END_NMN_PATTERN.matches(v) ||
            GIT_SHA_PATTERN.matches(v)
          )
      }
    }.toSet()
  }

  /**
   * Select a random set of versions to test
   */
  internal val RANGE_COUNT_LIMIT = 25

  /**
   * Select a random set of versions to test, limiting the range for efficiency.
   *
   * @param versions The set of versions to consider.
   * @return A limited set of versions for testing.
   */
  private fun limitLargeRanges(
    versions: Set<Version>,
    isEligible: (Version) -> Boolean,
    random: Random
  ): Set<Version> {
    val beforeSize = versions.size
    val versionSet = VersionSet(versions, isEligible)
    val selected = versionSet.sampleEligibleBoundaries(RANGE_COUNT_LIMIT - 1, random)
    val afterSize = selected.size

    if (beforeSize - afterSize > 0) {
      println("Muzzle skipping ${beforeSize - afterSize} versions")
    }

    return selected
  }
}
