package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.MavenRepoFixture
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.util.version.GenericVersionScheme
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class MuzzleMavenRepoUtilsTest {

  @TempDir
  lateinit var tempDir: File

  private val system = MuzzleMavenRepoUtils.newRepositorySystem()

  private val versionScheme = GenericVersionScheme()

  @Test
  fun `resolveVersionRange resolves all versions matching an open range`() {
    val repo = publishAndGetRepo("com.example", "mylib", listOf("1.0.0", "2.0.0", "3.0.0"))
    val directive = MuzzleDirective().apply {
      group = "com.example"
      module = "mylib"
      versions = "[1.0,)"
    }

    val result = MuzzleMavenRepoUtils.resolveVersionRange(directive, system, newSession(), listOf(repo))

    val resolvedVersions = result.versions.map { it.toString() }
    assertThat(resolvedVersions).containsExactly("1.0.0", "2.0.0", "3.0.0")
  }

  @Test
  fun `resolveVersionRange respects bounded version range`() {
    val repo = publishAndGetRepo("com.example", "mylib", listOf("1.0.0", "2.0.0", "3.0.0", "4.0.0", "5.0.0"))
    val directive = MuzzleDirective().apply {
      group = "com.example"
      module = "mylib"
      versions = "[2.0,4.0)"
    }

    val result = MuzzleMavenRepoUtils.resolveVersionRange(directive, system, newSession(), listOf(repo))

    val resolvedVersions = result.versions.map { it.toString() }
    assertThat(resolvedVersions).containsExactly("2.0.0", "3.0.0")
  }

  @Test
  fun `resolveVersionRange throws IllegalStateException when resolution consistently fails`() {
    val emptyRepo = RemoteRepository.Builder("empty", "default", File(tempDir, "empty").apply { mkdirs() }.toURI().toString()).build()
    val directive = MuzzleDirective().apply {
      group = "com.example"
      module = "nonexistent"
      versions = "[1.0,)"
    }

    assertThatThrownBy {
      MuzzleMavenRepoUtils.resolveVersionRange(directive, system, newSession(), listOf(emptyRepo))
    }.isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun `resolveVersionRange includes directive extra repositories`() {
    val repoA = publishAndGetRepo("com.example", "mylib", listOf("1.0.0", "2.0.0"), subDir = "repoA")
    val fixtureB = MavenRepoFixture(File(tempDir, "repoB"))
    fixtureB.publishVersions("com.example", "mylib", listOf("3.0.0"))
    val directive = MuzzleDirective().apply {
      group = "com.example"
      module = "mylib"
      versions = "[1.0,)"
      extraRepository("repoB", fixtureB.repoUrl)
    }

    val result = MuzzleMavenRepoUtils.resolveVersionRange(directive, system, newSession(), listOf(repoA))

    val resolvedVersions = result.versions.map { it.toString() }
    assertThat(resolvedVersions)
      .withFailMessage("Expected all 3 versions from both repos, got: $resolvedVersions")
      .containsAll(listOf("1.0.0", "2.0.0", "3.0.0"))
  }

  @Test
  fun `inverseOf returns directives outside range, inverts assertPass, and preserves properties`() {
    val repo = publishAndGetRepo("com.example", "mylib", listOf("1.0.0", "2.0.0", "3.0.0", "4.0.0", "5.0.0"))
    val directive = MuzzleDirective().apply {
      name = "mytest"
      group = "com.example"
      module = "mylib"
      versions = "[2.0,4.0)"
      assertPass = true
      excludeDependency("com.other:dep")
      includeSnapshots = false
    }

    val result = MuzzleMavenRepoUtils.inverseOf(directive, system, newSession(), listOf(repo))

    val resultVersions = result.map { it.versions }.toSet()
    // Versions inside [2.0, 4.0) are 2.0.0 and 3.0.0 — they should NOT appear
    assertThat(resultVersions).doesNotContain("2.0.0", "3.0.0")
    // Versions outside range: 1.0.0, 4.0.0, 5.0.0
    assertThat(resultVersions).contains("1.0.0", "4.0.0", "5.0.0")

    // assertPass must be inverted, and directive properties must be preserved
    assertThat(result).allSatisfy { directive ->
      assertThat(directive.assertPass).isFalse()
      assertThat(directive.name).isEqualTo("mytest")
      assertThat(directive.group).isEqualTo("com.example")
      assertThat(directive.module).isEqualTo("mylib")
      assertThat(directive.excludedDependencies).containsExactly("com.other:dep")
      assertThat(directive.includeSnapshots).isFalse()
    }
  }

  @ParameterizedTest(name = "[{index}] highest({0}, {1}) == {2}")
  @CsvSource(
    value =
      [
        "1.0.0, 2.0.0, 2.0.0",
        "2.0.0, 1.0.0, 2.0.0",
        "3.5.1, 3.5.1, 3.5.1", // equal — either is acceptable
      ])
  fun `highest returns the greater version`(a: String, b: String, expected: String) {
    val result = MuzzleMavenRepoUtils.highest(version(a), version(b))
    assertThat(result).isEqualTo(version(expected))
  }

  @ParameterizedTest(name = "[{index}] lowest({0}, {1}) == {2}")
  @CsvSource(
    value =
      [
        "1.0.0, 2.0.0, 1.0.0",
        "2.0.0, 1.0.0, 1.0.0",
        "3.5.1, 3.5.1, 3.5.1", // equal — either is acceptable
      ])
  fun `lowest returns the lesser version`(a: String, b: String, expected: String) {
    val result = MuzzleMavenRepoUtils.lowest(version(a), version(b))
    assertThat(result).isEqualTo(version(expected))
  }

  @Test
  fun `muzzleDirectiveToArtifacts throws GradleException when all versions are filtered out`() {
    val directive =
      MuzzleDirective().apply {
        group = "com.example"
        module = "test"
        includeSnapshots = false // SNAPSHOT and RC will be filtered
      }
    // All versions are pre-release; none survive filterAndLimitVersions
    val rangeResult = createVersionRangeResult("1.0.0-SNAPSHOT", "2.0.0-RC1")

    assertThatThrownBy {
      MuzzleMavenRepoUtils.muzzleDirectiveToArtifacts(directive, rangeResult)
    }.isInstanceOf(GradleException::class.java)
  }

  @Test
  fun `muzzleDirectiveToArtifacts produces artifacts with correct coordinates`() {
    val directive =
      MuzzleDirective().apply {
        group = "com.example"
        module = "mylib"
        // classifier is null → DefaultArtifact receives ""
        includeSnapshots = false
      }
    // Distinct major.minor versions so lowAndHighForMajorMinor keeps all three
    val rangeResult = createVersionRangeResult("1.0.0", "2.0.0", "3.0.0")

    val artifacts = MuzzleMavenRepoUtils.muzzleDirectiveToArtifacts(directive, rangeResult)

    assertThat(artifacts).hasSize(3)
    assertThat(artifacts).allSatisfy { artifact ->
      assertThat(artifact.groupId).isEqualTo("com.example")
      assertThat(artifact.artifactId).isEqualTo("mylib")
      assertThat(artifact.extension).isEqualTo("jar")
      assertThat(artifact.classifier).isEmpty()
    }
    assertThat(artifacts.map { it.version }).containsOnly("1.0.0", "2.0.0", "3.0.0")
  }

  @Test
  fun `muzzleDirectiveToArtifacts propagates classifier to artifacts`() {
    val directive =
      MuzzleDirective().apply {
        group = "com.example"
        module = "mylib"
        classifier = "tests"
        includeSnapshots = false
      }
    val rangeResult = createVersionRangeResult("1.0.0", "2.0.0")

    val artifacts = MuzzleMavenRepoUtils.muzzleDirectiveToArtifacts(directive, rangeResult)

    assertThat(artifacts).allSatisfy { assertThat(it.classifier).isEqualTo("tests") }
  }

  private fun newSession() = MuzzleMavenRepoUtils.newRepositorySystemSession(system)

  private fun publishAndGetRepo(
    group: String,
    module: String,
    versions: List<String>,
    subDir: String = "default"
  ): RemoteRepository {
    val fixture = MavenRepoFixture(File(tempDir, subDir))
    fixture.publishVersions(group, module, versions)
    return RemoteRepository.Builder(subDir, "default", fixture.repoUrl).build()
  }

  private fun version(v: String) = versionScheme.parseVersion(v)

  private fun createVersionRangeResult(vararg versionStrings: String): VersionRangeResult {
    val artifact = DefaultArtifact("com.example:test:[1.0,)")
    val request = VersionRangeRequest(artifact, emptyList(), null)
    val versions = versionStrings.map { versionScheme.parseVersion(it) }.sorted()
    // lowestVersion/highestVersion are computed as versions[0] and versions[last]
    return VersionRangeResult(request).apply { this.versions = versions }
  }
}
