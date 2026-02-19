package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.MavenRepoFixture
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.util.version.GenericVersionScheme
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

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
    assertEquals(listOf("1.0.0", "2.0.0", "3.0.0"), resolvedVersions)
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
    assertEquals(listOf("2.0.0", "3.0.0"), resolvedVersions)
  }

  @Test
  fun `resolveVersionRange throws IllegalStateException when resolution consistently fails`() {
    val emptyRepo = RemoteRepository.Builder("empty", "default", File(tempDir, "empty").apply { mkdirs() }.toURI().toString()).build()
    val directive = MuzzleDirective().apply {
      group = "com.example"
      module = "nonexistent"
      versions = "[1.0,)"
    }

    assertThrows<IllegalStateException> {
      MuzzleMavenRepoUtils.resolveVersionRange(directive, system, newSession(), listOf(emptyRepo))
    }
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
    assertTrue(resolvedVersions.containsAll(listOf("1.0.0", "2.0.0", "3.0.0"))) {
      "Expected all 3 versions from both repos, got: $resolvedVersions"
    }
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
    assertFalse(resultVersions.contains("2.0.0")) { "2.0.0 is inside range and must not appear in inverse" }
    assertFalse(resultVersions.contains("3.0.0")) { "3.0.0 is inside range and must not appear in inverse" }
    // Versions outside range: 1.0.0, 4.0.0, 5.0.0
    assertTrue(resultVersions.containsAll(listOf("1.0.0", "4.0.0", "5.0.0"))) {
      "Expected versions outside [2.0,4.0), got: $resultVersions"
    }

    // assertPass must be inverted
    assertTrue(result.all { !it.assertPass }) { "All inverse directives must have assertPass=false" }

    // Directive properties must be preserved
    assertTrue(result.all { it.name == "mytest" }) { "name must be preserved" }
    assertTrue(result.all { it.group == "com.example" }) { "group must be preserved" }
    assertTrue(result.all { it.module == "mylib" }) { "module must be preserved" }
    assertTrue(result.all { it.excludedDependencies == listOf("com.other:dep") }) { "excludedDependencies must be preserved" }
    assertTrue(result.all { !it.includeSnapshots }) { "includeSnapshots must be preserved" }
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
    assertEquals(version(expected), result)
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
    assertEquals(version(expected), result)
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

    assertThrows<GradleException> {
      MuzzleMavenRepoUtils.muzzleDirectiveToArtifacts(directive, rangeResult)
    }
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

    assertEquals(3, artifacts.size)
    assertTrue(artifacts.all { it.groupId == "com.example" }) { "All artifacts must have groupId 'com.example'" }
    assertTrue(artifacts.all { it.artifactId == "mylib" }) { "All artifacts must have artifactId 'mylib'" }
    assertTrue(artifacts.all { it.extension == "jar" }) { "All artifacts must have extension 'jar'" }
    assertTrue(artifacts.all { it.classifier == "" }) { "All artifacts must have empty classifier" }
    assertEquals(setOf("1.0.0", "2.0.0", "3.0.0"), artifacts.map { it.version }.toSet())
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

    assertTrue(artifacts.all { it.classifier == "tests" })
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
