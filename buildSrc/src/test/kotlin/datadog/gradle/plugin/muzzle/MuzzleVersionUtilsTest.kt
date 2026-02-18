package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.MuzzleVersionUtils.RANGE_COUNT_LIMIT
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.util.version.GenericVersionScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class MuzzleVersionUtilsTest {

  private val versionScheme = GenericVersionScheme()

  @ParameterizedTest(name = "[{index}] filters pre-release: {0}")
  @ValueSource(
    strings =
      [
        "2.0.0-SNAPSHOT", // -snapshot
        "2.0.0-RC1", // rc
        "2.0.0.CR1", // .cr
        "2.0.0-alpha", // alpha
        "2.0.0-beta.1", // beta
        "2.0.0-b2", // -b
        "2.0.0.M1", // .m
        "2.0.0-m1", // -m
        "2.0.0-dev", // -dev
        "2.0.0-ea", // -ea
        "2.0.0-atlassian-3", // -atlassian-
        "2.0-public_draft", // public_draft
        "2.0.0-cr1", // -cr
        "2.0-preview", // -preview
        "2.0.0.redhat-1", // redhat
        "2.7.3m2", // END_NMN_PATTERN  ^.*\.[0-9]+[mM][0-9]+$
        "2.0.0-1a2b3c4d", // GIT_SHA_PATTERN  ^.*-[0-9a-f]{7,}$
      ])
  fun `filterAndLimitVersions filters out pre-release versions when includeSnapshots is false`(
    preRelease: String
  ) {
    val result = createVersionRangeResult("1.0.0", preRelease, "3.0.0")

    val filtered =
      MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), includeSnapshots = false)

    assertFalse(filtered.any { it.toString() == preRelease }) {
      "Expected '$preRelease' to be filtered out"
    }
    assertTrue(filtered.any { it.toString() == "1.0.0" })
    assertTrue(filtered.any { it.toString() == "3.0.0" })
  }

  @ParameterizedTest(name = "[{index}] includeSnapshots=true keeps ''{0}'', skipVersions={1}")
  @MethodSource("includeSnapshotsCases")
  fun `with includeSnapshots=true, keeps pre-release versions and still respects skipVersions`(
    preRelease: String,
    skipVersions: Set<String>
  ) {
    // preRelease major.minor = 1.0, surrounded by 2.0 and 3.0-RC1 (distinct major.minor)
    val result = createVersionRangeResult(preRelease, "2.0.0", "3.0.0-RC1")

    val filtered =
      MuzzleVersionUtils.filterAndLimitVersions(result, skipVersions, includeSnapshots = true)

    assertTrue(filtered.any { it.toString() == preRelease }) {
      "Expected '$preRelease' to be kept when includeSnapshots=true"
    }
    skipVersions.forEach { skipped ->
      assertFalse(filtered.any { it.toString() == skipped }) {
        "Expected '$skipped' to be absent due to skipVersions"
      }
    }
  }

  @ParameterizedTest(name = "[{index}] skips exact version: {0}")
  @ValueSource(strings = ["1.1.0", "1.3.0", "2.0.0"])
  fun `can skip exact versions`(versionToSkip: String) {
    val result = createVersionRangeResult("1.0.0", "1.1.0", "1.2.0", "1.3.0", "2.0.0", "3.0.0")

    val filtered =
      MuzzleVersionUtils.filterAndLimitVersions(
        result, setOf(versionToSkip), includeSnapshots = false)

    assertFalse(filtered.any { it.toString() == versionToSkip })
  }

  @Test
  fun `skip versions is case sensitive`() {
    val result = createVersionRangeResult("1.0.0", "2.0.0-custom", "3.0.0")

    val filtered =
      MuzzleVersionUtils.filterAndLimitVersions(
        result, setOf("2.0.0-Custom"), includeSnapshots = false)

    assertTrue(filtered.any { it.toString() == "2.0.0-custom" }) {
      "Expected '2.0.0-custom' to be kept because skipVersions entry 'Custom' does not match lowercased 'custom'"
    }
  }

  @Test
  fun `trim version range larger than the limit`() {
    // 30 versions with distinct major.minor: 1.0.0, 1.1.0, ..., 1.29.0
    val versions = (0..29).map { "1.$it.0" }.toTypedArray()
    val result = createVersionRangeResult(*versions)

    val filtered =
      MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), includeSnapshots = false)

    assertTrue(filtered.size < RANGE_COUNT_LIMIT) { "Expected fewer than 25 versions after trimming, got ${filtered.size}" }
    assertTrue(filtered.isNotEmpty())
    assertTrue(filtered.any { it == result.lowestVersion }) {
      "lowestVersion (${result.lowestVersion}) must be preserved"
    }
    assertTrue(filtered.any { it == result.highestVersion }) {
      "highestVersion (${result.highestVersion}) must be preserved"
    }
    val originalSet = versions.toSet()
    assertTrue(filtered.all { it.toString() in originalSet }) {
      "All filtered versions must come from the original set"
    }
  }

  @ParameterizedTest(name = "[{index}] {0} version(s) pass through unchanged")
  @ValueSource(ints = [1, 2, 3, 10, 24])
  fun `should limit large ranges`(count: Int) {
    val versionStrings = (0 until count).map { "$it.0.0" }.toTypedArray()
    val result = createVersionRangeResult(*versionStrings)

    val filtered =
      MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), includeSnapshots = false)

    assertEquals(count, filtered.size)
    versionStrings.forEach { v -> assertTrue(filtered.any { it.toString() == v }) }
  }

  companion object {
    @JvmStatic
    fun includeSnapshotsCases() = listOf(
        Arguments.of("1.0.0-SNAPSHOT", emptySet<String>()),
        Arguments.of("1.0.0-RC1", emptySet<String>()),
        Arguments.of("1.0.0-alpha", emptySet<String>()),
        Arguments.of("1.0.0-beta.1", emptySet<String>()),
        Arguments.of("1.0.0-b2", emptySet<String>()),
        // skipVersions is still respected even when includeSnapshots=true
        Arguments.of("1.0.0-SNAPSHOT", setOf("2.0.0")),
      )
  }

  private fun createVersionRangeResult(vararg versionStrings: String): VersionRangeResult {
    val artifact = DefaultArtifact("com.example:test:[1.0,)")
    val request = VersionRangeRequest(artifact, emptyList(), null)
    val versions = versionStrings.map { versionScheme.parseVersion(it) }.sorted()
    // lowestVersion/highestVersion are computed as versions[0] and versions[last]
    return VersionRangeResult(request).apply { this.versions = versions }
  }
}

