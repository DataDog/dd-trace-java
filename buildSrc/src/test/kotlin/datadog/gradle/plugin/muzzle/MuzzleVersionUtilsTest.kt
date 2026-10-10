package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.MuzzleVersionUtils.RANGE_COUNT_LIMIT
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.util.version.GenericVersionScheme
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import kotlin.random.Random

class MuzzleVersionUtilsTest {

  private val versionScheme = GenericVersionScheme()

  @Test
  fun `sampling seed reproduces large ranges while preserving extrema`() {
    val versions = (0..30).flatMap { minor -> listOf("1.$minor.0", "1.$minor.1") }
    val result = createVersionRangeResult(*versions.toTypedArray())
    val first = MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), false, Random(42))
    val replay = MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), false, Random(42))
    val nextBuild = MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), false, Random(43))
    assertThat(replay).containsExactlyElementsOf(first)
    assertThat(first).contains(result.lowestVersion, result.highestVersion).hasSize(RANGE_COUNT_LIMIT - 1)
    assertThat(nextBuild).contains(result.lowestVersion, result.highestVersion).isNotEqualTo(first)
  }

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

    val filteredStrings = filtered.map { it.toString() }
    assertThat(filteredStrings).withFailMessage("Expected '$preRelease' to be filtered out").doesNotContain(preRelease)
    assertThat(filteredStrings).contains("1.0.0", "3.0.0")
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

    val filteredStrings = filtered.map { it.toString() }
    assertThat(filteredStrings)
      .withFailMessage("Expected '$preRelease' to be kept when includeSnapshots=true")
      .contains(preRelease)
    skipVersions.forEach { skipped ->
      assertThat(filteredStrings)
        .withFailMessage("Expected '$skipped' to be absent due to skipVersions")
        .doesNotContain(skipped)
    }
  }

  @ParameterizedTest(name = "[{index}] skips exact version: {0}")
  @ValueSource(strings = ["1.1.0", "1.3.0", "2.0.0"])
  fun `can skip exact versions`(versionToSkip: String) {
    val result = createVersionRangeResult("1.0.0", "1.1.0", "1.2.0", "1.3.0", "2.0.0", "3.0.0")

    val filtered =
      MuzzleVersionUtils.filterAndLimitVersions(
        result, setOf(versionToSkip), includeSnapshots = false)

    assertThat(filtered.map { it.toString() }).doesNotContain(versionToSkip)
  }

  @Test
  fun `skip versions is case sensitive`() {
    val result = createVersionRangeResult("1.0.0", "2.0.0-custom", "3.0.0")

    val filtered =
      MuzzleVersionUtils.filterAndLimitVersions(
        result, setOf("2.0.0-Custom"), includeSnapshots = false)

    assertThat(filtered.map { it.toString() })
      .withFailMessage("Expected '2.0.0-custom' to be kept because skipVersions entry 'Custom' does not match lowercased 'custom'")
      .contains("2.0.0-custom")
  }

  @Test
  fun `trim version range larger than the limit`() {
    // 30 versions with distinct major.minor: 1.0.0, 1.1.0, ..., 1.29.0
    val versions = (0..29).map { "1.$it.0" }.toTypedArray()
    val result = createVersionRangeResult(*versions)

    val filtered =
      MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), includeSnapshots = false)

    assertThat(filtered).withFailMessage("Expected fewer than 25 versions after trimming, got ${filtered.size}")
        .hasSizeLessThan(RANGE_COUNT_LIMIT)
    assertThat(filtered).isNotEmpty()
    val filteredStrings = filtered.map { it.toString() }
    assertThat(filteredStrings).withFailMessage("lowestVersion (${result.lowestVersion}) must be preserved")
        .contains(result.lowestVersion.toString())
    assertThat(filteredStrings).withFailMessage("highestVersion (${result.highestVersion}) must be preserved")
        .contains(result.highestVersion.toString())
    assertThat(filteredStrings).withFailMessage("All filtered versions must come from the original set")
        .isSubsetOf(*versions)
  }

  @ParameterizedTest(name = "[{index}] {0} version(s) pass through unchanged")
  @ValueSource(ints = [1, 2, 3, 10, 24])
  fun `should limit large ranges`(count: Int) {
    val versionStrings = (0 until count).map { "$it.0.0" }.toTypedArray()
    val result = createVersionRangeResult(*versionStrings)

    val filtered =
      MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), includeSnapshots = false)

    assertThat(filtered.map { it.toString() }).containsExactlyInAnyOrder(*versionStrings)
  }

  @Test
  fun `checks age only after prerelease and skip exclusions`() {
    val result = createVersionRangeResult("1.0.0", "1.1.0", "1.2.0-RC1", "1.3.0")
    val checked = mutableListOf<String>()

    val filtered = MuzzleVersionUtils.filterAndLimitVersions(result, setOf("1.1.0"), false) {
      checked.add(it.toString())
      it.toString() != "1.3.0"
    }

    assertThat(checked).containsExactly("1.0.0", "1.3.0")
    assertThat(filtered.map { it.toString() }).containsExactly("1.0.0")
  }

  @Test
  fun `skips exact prerelease spelling before checking age`() {
    val result = createVersionRangeResult("1.0.0", "2.0.0-SNAPSHOT")
    val checked = mutableListOf<String>()

    val filtered = MuzzleVersionUtils.filterAndLimitVersions(result, setOf("2.0.0-SNAPSHOT"), true) {
      checked.add(it.toString())
      true
    }

    assertThat(checked).containsExactly("1.0.0")
    assertThat(filtered.map { it.toString() }).containsExactly("1.0.0")
  }

  @Test
  fun `selects the previous eligible patch before sampling a minor version`() {
    val result = createVersionRangeResult("1.0.0", "1.0.1", "1.0.2")

    val filtered = MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), false) {
      it.toString() != "1.0.2"
    }

    assertThat(filtered.map { it.toString() }).containsExactlyInAnyOrder("1.0.0", "1.0.1")
  }

  @Test
  fun `preserves eligible bounds when original bounds are deferred`() {
    val result = createVersionRangeResult(*(0..49).map { "1.$it.0" }.toTypedArray())

    repeat(10) {
      val filtered = MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), false) {
        it.toString() != "1.0.0" && it.toString() != "1.49.0"
      }
      assertThat(filtered.map { it.toString() })
        .contains("1.1.0", "1.48.0").doesNotContain("1.0.0", "1.49.0")
      assertThat(filtered).hasSizeLessThan(RANGE_COUNT_LIMIT)
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `large patch histories only look up timestamps for eligible boundaries`(timestampAvailable: Boolean) {
    val now = Instant.parse("2026-10-01T00:00:00Z")
    val proxy = RemoteRepository.Builder("central-proxy", "default", "https://proxy.example/maven2/").build()
    val requests = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val age = MuzzleDependencyAge(48, now, { url ->
      requests.add(url)
      if (timestampAvailable && url.startsWith("https://repo1.maven.org/")) {
        MuzzleDependencyAge.Timestamp(now.minusSeconds(72 * 3600L))
      } else {
        MuzzleDependencyAge.Timestamp(null, "missing Last-Modified header")
      }
    }, warnings::add)
    val versions = (0..49).flatMap { minor -> (0..19).map { patch -> "1.$minor.$patch" } }

    val filtered = MuzzleVersionUtils.filterAndLimitVersions(createVersionRangeResult(*versions.toTypedArray()), emptySet(), false) {
      age.isEligible("com.example", "lib", it.toString(), listOf(proxy))
    }

    assertThat(filtered).hasSize(24)
    assertThat(filtered.map { it.toString() }).contains("1.0.0", "1.49.19")
    assertThat(requests).hasSize(48).doesNotHaveDuplicates()
    assertThat(warnings).hasSize(if (timestampAvailable) 0 else 24)
  }

  @Test
  fun `eligible ranges preserve the master sample for the same random seed`() {
    val result = createVersionRangeResult(*(0..49).flatMap { minor -> (0..19).map { patch -> "1.$minor.$patch" } }.toTypedArray())
    repeat(100) { seed ->
      val expected = VersionSet(result.versions).lowAndHighForMajorMinor.shuffled(Random(seed)).toMutableList()
      while (expected.size >= RANGE_COUNT_LIMIT) {
        val removed = expected.removeAt(0)
        if (removed == result.lowestVersion || removed == result.highestVersion) expected.add(removed)
      }

      val actual = MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), false, random = Random(seed), isEligible = { true })

      assertThat(actual).containsExactlyInAnyOrderElementsOf(expected)
    }
  }

  @Test
  fun `sampled fresh boundaries are backfilled and still produce 24 checks`() {
    val result = createVersionRangeResult(*(0..49).flatMap { minor -> (0..19).map { patch -> "1.$minor.$patch" } }.toTypedArray())
    val checked = mutableListOf<String>()

    val actual = MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), false, random = Random(17)) {
      checked.add(it.toString())
      !it.toString().endsWith(".19")
    }

    assertThat(actual).hasSize(24)
    assertThat(actual.map { it.toString() }).contains("1.0.0", "1.49.18").noneMatch { it.endsWith(".19") }
    assertThat(checked).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(48)
  }

  @Test
  fun `walks past fresh boundaries without checking interior patches`() {
    val versions = (0..999).map { "1.0.$it" }.toTypedArray()
    val checked = mutableListOf<String>()

    val filtered = MuzzleVersionUtils.filterAndLimitVersions(createVersionRangeResult(*versions), emptySet(), false) {
      checked.add(it.toString())
      it.toString() !in setOf("1.0.998", "1.0.999")
    }

    assertThat(filtered.map { it.toString() }).containsExactlyInAnyOrder("1.0.0", "1.0.997")
    assertThat(checked).containsExactlyInAnyOrder("1.0.0", "1.0.999", "1.0.998", "1.0.997")
  }

  @Test
  fun `retains the sole eligible interior patch and omits entirely deferred minor versions`() {
    val result = createVersionRangeResult("1.0.0", "1.0.1", "1.0.2", "1.1.0", "1.1.1", "1.2.0")
    val checked = mutableListOf<String>()

    val filtered = MuzzleVersionUtils.filterAndLimitVersions(result, emptySet(), false) {
      checked.add(it.toString())
      it.toString() == "1.0.1"
    }

    assertThat(filtered.map { it.toString() }).containsExactly("1.0.1")
    assertThat(checked).doesNotHaveDuplicates()
  }

  @Test
  fun `empty and entirely deferred ranges produce no versions`() {
    for (versions in listOf(emptyArray(), arrayOf("1.0.0"), arrayOf("1.0.0", "1.0.1", "1.1.0"))) {
      val checked = mutableListOf<String>()
      val filtered = MuzzleVersionUtils.filterAndLimitVersions(createVersionRangeResult(*versions), emptySet(), false) {
        checked.add(it.toString())
        false
      }

      assertThat(filtered).isEmpty()
      assertThat(checked).containsExactlyInAnyOrder(*versions)
    }
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

