package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.MavenRepoFixture
import datadog.gradle.plugin.muzzle.MuzzleDependencyAge
import datadog.gradle.plugin.muzzle.MuzzleDirective
import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.time.Instant

class MavenMuzzleResolutionServiceTest {
  @TempDir
  lateinit var tempDir: File

  private val now = Instant.parse("2026-09-30T12:00:00Z")
  private val warnings = mutableListOf<String>()
  private val system = MuzzleMavenRepoUtils.newRepositorySystem()

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `pass and fail directives exclude fresh versions but retain unverified versions`(assertPass: Boolean) {
    val fixture = MavenRepoFixture(tempDir)
    fixture.publishVersions("com.example", "lib", listOf("1.0.0", "1.1.0", "1.3.0"))
    fixture.publishVersions("com.example", "lib", listOf("1.2.0"), publishedAt = now)
    check(File(fixture.repoDir, "com/example/lib/1.3.0/lib-1.3.0.pom").delete())
    val directive = directive().apply { this.assertPass = assertPass }

    val artifacts = service(fixture).resolveArtifacts(directive)

    assertThat(artifacts.map { it.version }).containsExactlyInAnyOrder("1.0.0", "1.1.0", "1.3.0")
    assertThat(warnings).anySatisfy { assertThat(it).contains("com.example:lib:1.2.0", "eligible at") }
      .anySatisfy { assertThat(it).contains("com.example:lib:1.3.0", "cannot verify") }
  }

  @Test
  fun `all deferred direct versions fail with a clear cooldown error`() {
    val fixture = MavenRepoFixture(tempDir)
    fixture.publishVersions("com.example", "lib", listOf("1.0.0"), publishedAt = now)

    assertThatThrownBy { service(fixture).resolveArtifacts(directive()) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("No eligible muzzle artifacts for com.example:lib")
      .hasMessageContaining("48h publication cooldown")
      .hasMessageContaining("-PmuzzleMinDependencyAgeHours=0")
      .hasNoCause()
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `version exclusions fail with the same clear empty-selection error`(skipExactVersion: Boolean) {
    val fixture = MavenRepoFixture(tempDir)
    val version = if (skipExactVersion) "1.1.0" else "1.1.0-SNAPSHOT"
    fixture.publishVersions("com.example", "lib", listOf(version))
    val directive = directive().apply {
      if (skipExactVersion) skipVersions.add(version)
    }

    assertThatThrownBy { service(fixture).resolveArtifacts(directive) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("No eligible muzzle artifacts for com.example:lib")
      .hasMessageContaining("after version exclusions and 48h publication cooldown")
      .hasNoCause()
  }

  @Test
  fun `inverse selection defers fresh versions and retains extra repositories`() {
    val central = MavenRepoFixture(File(tempDir, "central"))
    central.publishVersions("com.example", "lib", listOf("1.0.0", "2.0.0"))
    central.publishVersions("com.example", "lib", listOf("3.0.0"), publishedAt = now)
    val extra = MavenRepoFixture(File(tempDir, "extra"))
    extra.publishVersions("com.example", "lib", listOf("0.9.0"))
    val directive = directive().apply {
      assertInverse = true
      extraRepository("extra", extra.repoUrl)
    }

    val plans = MuzzleTaskPlanner(service(central)).plan(listOf(directive))

    assertThat(plans.filter { it.directive.assertPass }.map { it.artifact?.version })
      .containsExactly("1.0.0")
    assertThat(plans.filter { !it.directive.assertPass }.map { it.artifact?.version })
      .containsExactlyInAnyOrder("0.9.0", "2.0.0")
    assertThat(warnings).singleElement().asString().contains("com.example:lib:3.0.0")
  }

  @Test
  fun `empty eligible inverse selection adds no checks`() {
    val fixture = MavenRepoFixture(tempDir)
    fixture.publishVersions("com.example", "lib", listOf("1.0.0"))
    fixture.publishVersions("com.example", "lib", listOf("2.0.0"), publishedAt = now)

    val plans = MuzzleTaskPlanner(service(fixture)).plan(listOf(directive().apply { assertInverse = true }))

    assertThat(plans).hasSize(1)
    assertThat(plans.single().artifact?.version).isEqualTo("1.0.0")
  }

  @Test
  fun `Central timestamp fallback defers fresh direct and inverse versions without changing discovery repositories`() {
    val fixture = MavenRepoFixture(tempDir)
    fixture.publishVersions("com.example", "lib", listOf("1.0.0", "1.1.0", "2.0.0", "3.0.0"))
    val proxy = RemoteRepository.Builder("central-proxy", "default", "https://proxy.example/maven2/").build()
    val local = RemoteRepository.Builder("fixture", "default", fixture.repoUrl).build()
    val discoverySystem = object : RepositorySystem by system {
      override fun resolveVersionRange(session: RepositorySystemSession, request: VersionRangeRequest): VersionRangeResult {
        assertThat(request.repositories).containsExactly(proxy)
        // Serve proxy metadata from local files without allowing discovery to contact Central.
        return system.resolveVersionRange(session, VersionRangeRequest(request.artifact, listOf(local), request.requestContext))
      }
    }
    val requests = mutableListOf<String>()
    val age = MuzzleDependencyAge(48, now, { url ->
      requests.add(url)
      if (url.startsWith(proxy.url)) {
        MuzzleDependencyAge.Timestamp(null, "missing or invalid Last-Modified header")
      } else {
        assertThat(url).startsWith("https://repo1.maven.org/maven2/")
        val fresh = url.contains("/1.1.0/") || url.contains("/3.0.0/")
        MuzzleDependencyAge.Timestamp(if (fresh) now else now.minusSeconds(72 * 3600L))
      }
    }, warnings::add)
    val service = MavenMuzzleResolutionService(
      discoverySystem,
      MuzzleMavenRepoUtils.newRepositorySystemSession(system),
      age,
      listOf(proxy)
    )

    val plans = MuzzleTaskPlanner(service).plan(listOf(directive().apply { assertInverse = true }))

    assertThat(plans.filter { it.directive.assertPass }.map { it.artifact?.version }).containsExactly("1.0.0")
    assertThat(plans.filter { !it.directive.assertPass }.map { it.artifact?.version }).containsExactly("2.0.0")
    assertThat(requests).hasSize(8)
    assertThat(warnings).hasSize(2).allSatisfy {
      assertThat(it).contains("Muzzle deferring").doesNotContain("cannot verify")
    }
  }

  @Test
  fun `unverified direct and inverse versions remain planned`() {
    val fixture = MavenRepoFixture(tempDir)
    fixture.publishVersions("com.example", "lib", listOf("1.0.0", "2.0.0"))
    for (version in listOf("1.0.0", "2.0.0")) {
      check(File(fixture.repoDir, "com/example/lib/$version/lib-$version.pom").delete())
    }

    val plans = MuzzleTaskPlanner(service(fixture)).plan(listOf(directive().apply { assertInverse = true }))

    assertThat(plans.filter { it.directive.assertPass }.map { it.artifact?.version })
      .containsExactly("1.0.0")
    assertThat(plans.filter { !it.directive.assertPass }.map { it.artifact?.version })
      .containsExactly("2.0.0")
    assertThat(warnings).anySatisfy { assertThat(it).contains("Muzzle retaining com.example:lib:1.0.0", "cannot verify") }
      .anySatisfy { assertThat(it).contains("Muzzle retaining com.example:lib:2.0.0", "cannot verify") }
  }

  @Test
  fun `zero override includes fresh and unverified versions`() {
    val fixture = MavenRepoFixture(tempDir)
    fixture.publishVersions("com.example", "lib", listOf("1.0.0", "1.1.0"), publishedAt = now)
    check(File(fixture.repoDir, "com/example/lib/1.1.0/lib-1.1.0.pom").delete())

    assertThat(service(fixture, 0).resolveArtifacts(directive()).map { it.version })
      .containsExactlyInAnyOrder("1.0.0", "1.1.0")
    assertThat(warnings).isEmpty()
  }

  private fun service(fixture: MavenRepoFixture, minimumAgeHours: Int = 48) = MavenMuzzleResolutionService(
    system,
    MuzzleMavenRepoUtils.newRepositorySystemSession(system),
    MuzzleDependencyAge(minimumAgeHours, now, warn = warnings::add),
    listOf(RemoteRepository.Builder("fixture", "default", fixture.repoUrl).build())
  )

  private fun directive() = MuzzleDirective().apply {
    group = "com.example"
    module = "lib"
    versions = "[1.0,2.0)"
    assertPass = true
  }
}
