package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.MavenRepoFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.math.abs

class MuzzleDependencyAgeFunctionalTest : MuzzlePluginTestFixture() {
  private val task = ":dd-java-agent:instrumentation:demo:muzzle"
  private val assertionPrefix = "$task-AssertPass-com.example.test-demo-lib-"

  @Test
  fun `default cooldown selects eligible versions across modules`() {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.1.0"), publishedAt = Instant.now())
    val script = libraryProject(fixture)
    writeProject(script)
    addSubproject("dd-java-agent:instrumentation:other", script)
    writeNoopScanPlugin()

    val result = run(
      "muzzle",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl),
      unsetEnv = setOf("MIN_DEPENDENCY_AGE_HOURS")
    )

    assertThat(result.output).contains("BUILD SUCCESSFUL", "48h cooldown", "com.example.test:demo-lib:1.1.0")
    for (project in listOf("demo", "other")) {
      val prefix = ":dd-java-agent:instrumentation:$project:muzzle-AssertPass-com.example.test-demo-lib-"
      assertThat(result.task("${prefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
      assertThat(result.task("${prefix}1.1.0")).isNull()
    }
  }

  @Test
  fun `checks the dependency when its publication age cannot be verified`() {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    // File URLs omit Last-Modified when the modification time is zero.
    val pom = fixture.repoDir.resolve("com/example/test/demo-lib/1.0.0/demo-lib-1.0.0.pom")
    check(pom.setLastModified(0L))
    writeProject(libraryProject(fixture))
    writeNoopScanPlugin()

    val result = run(
      task,
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "48")
    )

    assertThat(result.output).contains("BUILD SUCCESSFUL", "Muzzle retaining com.example.test:demo-lib:1.0.0", "cannot verify publication age")
    assertThat(result.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `zero property overrides the environment and checks fresh releases`() {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"), publishedAt = Instant.now())
    writeProject(libraryProject(fixture))
    writeNoopScanPlugin()

    val result = run(
      task,
      "-PmuzzleMinDependencyAgeHours=0",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "96")
    )

    assertThat(result.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.output).doesNotContain("Muzzle deferring")
  }

  @Test
  fun `reused daemon reads cooldown configuration from each build`() {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions(
      "com.example.test",
      "demo-lib",
      listOf("1.0.0"),
      publishedAt = Instant.now().minusSeconds(72 * 3600L)
    )
    writeProject(libraryProject(fixture))
    writeNoopScanPlugin()

    val deferred = run(
      task,
      expectFailure = true,
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "96")
    )
    assertThat(deferred.output).contains("No eligible muzzle artifacts", "96h publication cooldown")

    val eligible = run(
      task,
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "48")
    )
    assertThat(eligible.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `reconsiders publication timestamps when configuration caching is requested`() {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.1.0"), publishedAt = Instant.now())
    writeProject(libraryProject(fixture))
    writeNoopScanPlugin()
    val environment = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "48")

    val first = run(task, "--configuration-cache", env = environment)
    assertThat(first.output).contains("BUILD SUCCESSFUL")
    assertThat(first.task("${assertionPrefix}1.1.0")).isNull()

    val pom = fixture.repoDir.resolve("com/example/test/demo-lib/1.1.0/demo-lib-1.1.0.pom")
    check(pom.setLastModified(Instant.now().minusSeconds(72 * 3600L).toEpochMilli()))

    val second = run(task, "--configuration-cache", env = environment)
    assertThat(second.output).contains("BUILD SUCCESSFUL").doesNotContain("Reusing configuration cache")
    assertThat(second.task("${assertionPrefix}1.1.0")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `invalid cooldown fails before dependency resolution`() {
    val fixture = createMavenRepoFixture()
    writeProject(libraryProject(fixture))

    val result = run(
      task,
      "-PmuzzleMinDependencyAgeHours=-1",
      expectFailure = true,
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl)
    )

    assertThat(result.output).contains("must be a non-negative integer")
      .doesNotContain("Muzzle version range resolution failed")
  }

  @ParameterizedTest
  @ValueSource(strings = ["runMuzzle", ":runMuzzle"])
  fun `aggregate does not resolve dependencies outside its selected slot`(aggregate: String) {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    writeProject(libraryProject(fixture))
    writeNoopScanPlugin()
    writeRootProject("""plugins { id("dd-trace-java.ci-jobs") }""")
    val selectedSlot = abs(":dd-java-agent:instrumentation:demo".hashCode() % 8) + 1
    val otherPath = (0..7).map { "dd-java-agent:instrumentation:other$it" }
      .first { abs(":$it".hashCode() % 8) + 1 != selectedSlot }
    // Resolving this deliberately absent library would fail the build.
    addSubproject(otherPath, libraryProject(fixture).replace("demo-lib", "absent-lib"))

    val result = run(
      aggregate,
      "-Pslot=$selectedSlot/8",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "48")
    )

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.tasks).noneMatch { it.path.startsWith(":$otherPath:") }
    assertThat(result.output).doesNotContain("absent-lib")
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `explicit module checks are planned outside the selected slot`(withAggregate: Boolean) {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    writeProject(libraryProject(fixture))
    writeNoopScanPlugin()
    writeRootProject("""plugins { id("dd-trace-java.ci-jobs") }""")
    val moduleSlot = abs(":dd-java-agent:instrumentation:demo".hashCode() % 8) + 1
    val selectedSlot = moduleSlot % 8 + 1
    val tasks = if (withAggregate) arrayOf(":runMuzzle", task) else arrayOf(task)

    val result = run(
      *tasks,
      "-Pslot=$selectedSlot/8",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "48")
    )

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
  }

  @ParameterizedTest
  @ValueSource(strings = ["", "/8", "invalid"])
  fun `aggregate without a valid slot still plans all modules`(slot: String) {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    val script = libraryProject(fixture)
    writeProject(script)
    addSubproject("dd-java-agent:instrumentation:other", script)
    writeNoopScanPlugin()
    writeRootProject("""plugins { id("dd-trace-java.ci-jobs") }""")
    val slotArgument = if (slot.isEmpty()) emptyArray() else arrayOf("-Pslot=$slot")

    val result = run(
      ":runMuzzle",
      *slotArgument,
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "48")
    )

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    for (project in listOf("demo", "other")) {
      assertThat(result.task(":dd-java-agent:instrumentation:$project:muzzle-AssertPass-com.example.test-demo-lib-1.0.0")?.outcome)
        .isEqualTo(SUCCESS)
    }
  }

  private fun libraryProject(fixture: MavenRepoFixture) = """
    plugins {
      id("java")
      id("dd-trace-java.muzzle")
    }
    repositories { maven { url = uri("${fixture.repoUrl}") } }
    muzzle {
      pass {
        group = "com.example.test"
        module = "demo-lib"
        versions = "[1.0,2.0)"
      }
    }
  """
}
