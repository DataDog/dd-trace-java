package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.MavenRepoFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
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
    assertThat(first.output).contains("BUILD SUCCESSFUL", "Configuration cache entry stored")
    assertThat(first.task("${assertionPrefix}1.1.0")).isNull()

    val unchanged = run(task, "--configuration-cache", env = environment)
    assertThat(unchanged.output).contains("BUILD SUCCESSFUL", "Reusing configuration cache")
    assertThat(unchanged.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(UP_TO_DATE)
    assertThat(unchanged.task("${assertionPrefix}1.1.0")).isNull()

    val pom = fixture.repoDir.resolve("com/example/test/demo-lib/1.1.0/demo-lib-1.1.0.pom")
    check(pom.setLastModified(Instant.now().minusSeconds(72 * 3600L).toEpochMilli()))

    val second = run(task, "--configuration-cache", env = environment)
    assertThat(second.output).contains("BUILD SUCCESSFUL").doesNotContain("Reusing configuration cache")
    assertThat(second.task("${assertionPrefix}1.1.0")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `a deferred incompatible release fails validation after becoming eligible with configuration caching`() {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.1.0"), publishedAt = Instant.now())
    writeProject(libraryProject(fixture))
    writeScanPlugin(
      """
      try (java.io.InputStream input = testApplicationClassLoader.getResourceAsStream(
          "META-INF/maven/com.example.test/demo-lib/pom.properties")) {
        java.util.Properties properties = new java.util.Properties();
        properties.load(input);
        if ("1.1.0".equals(properties.getProperty("version"))) {
          throw new IllegalStateException("Newly eligible version is incompatible");
        }
      } catch (java.io.IOException e) {
        throw new AssertionError(e);
      }
    """
    )
    val environment = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "48")

    val first = run(task, "--configuration-cache", env = environment)
    assertThat(first.output).contains("BUILD SUCCESSFUL", "Configuration cache entry stored")
    assertThat(first.task("${assertionPrefix}1.1.0")).isNull()

    val pom = fixture.repoDir.resolve("com/example/test/demo-lib/1.1.0/demo-lib-1.1.0.pom")
    check(pom.setLastModified(Instant.now().minusSeconds(72 * 3600L).toEpochMilli()))

    val second = run(task, "--configuration-cache", expectFailure = true, env = environment)
    assertThat(second.output).contains("Muzzle validation failed").doesNotContain("Reusing configuration cache")
    assertThat(second.task("${assertionPrefix}1.1.0")?.outcome).isEqualTo(FAILED)
    assertThat(resultFile("muzzle-AssertPass-com.example.test-demo-lib-1.1.0").toFile().readText())
      .contains("Newly eligible version is incompatible")
  }

  @Test
  fun `DSL cooldown overrides lazily configured property and environment defaults`() {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"), publishedAt = Instant.now())
    writeProject(libraryProject(fixture) + "\nmuzzle { minimumDependencyAgeHours.set(0) }")
    writeNoopScanPlugin()

    val result = run(
      task,
      "-PmuzzleMinDependencyAgeHours=invalid",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "invalid")
    )

    assertThat(result.output).contains("BUILD SUCCESSFUL").doesNotContain("Muzzle deferring")
    assertThat(result.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `each module may override its cooldown without changing other modules`() {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.1.0"), publishedAt = Instant.now())
    writeProject(libraryProject(fixture) + "\nmuzzle { minimumDependencyAgeHours.set(0) }")
    addSubproject("dd-java-agent:instrumentation:other", libraryProject(fixture))
    writeNoopScanPlugin()

    val result = run(
      "muzzle",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "48")
    )

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.task("${assertionPrefix}1.1.0")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:other:muzzle-AssertPass-com.example.test-demo-lib-1.1.0")).isNull()
  }

  @ParameterizedTest
  @ValueSource(strings = ["compileMuzzle", "compileJava"])
  fun `compilation does not resolve versions or cooldown configuration`(compilationTask: String) {
    val fixture = createMavenRepoFixture()
    writeProject(libraryProject(fixture).replace("demo-lib", "absent-lib"))
    writeNoopScanPlugin()

    val result = run(
      ":dd-java-agent:instrumentation:demo:$compilationTask",
      "--configuration-cache",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl, "MIN_DEPENDENCY_AGE_HOURS" to "invalid")
    )

    assertThat(result.output).contains("BUILD SUCCESSFUL", "Configuration cache entry stored")
      .doesNotContain("Muzzle retaining", "Muzzle deferring", "Muzzle version range resolution failed")
  }

  @Test
  fun `zero cooldown and large sampled ranges reuse an unchanged configuration cache`() {
    val fixture = createMavenRepoFixture()
    fixture.publishVersions("com.example.test", "demo-lib", (0..49).map { "1.$it.0" }, publishedAt = Instant.now())
    writeProject(libraryProject(fixture) + "\nmuzzle { minimumDependencyAgeHours.set(0) }")
    writeNoopScanPlugin()
    val environment = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl)

    val first = run(task, "--configuration-cache", env = environment)
    val second = run(task, "--configuration-cache", env = environment)

    assertThat(first.output).contains("BUILD SUCCESSFUL", "Configuration cache entry stored")
    assertThat(second.output).contains("BUILD SUCCESSFUL", "Reusing configuration cache")
    assertThat(first.tasks.filter { it.path.startsWith(assertionPrefix) }).hasSize(24)
    assertThat(second.tasks.filter { it.path.startsWith(assertionPrefix) }.map { it.path })
      .containsExactlyElementsOf(first.tasks.filter { it.path.startsWith(assertionPrefix) }.map { it.path })
  }

  @Test
  fun `core JDK checks reuse configuration cache without publication lookups`() {
    writeProject(
      """
      plugins { id("java"); id("dd-trace-java.muzzle") }
      muzzle { pass { coreJdk() } }
    """
    )
    writeNoopScanPlugin()

    val first = run(task, "--configuration-cache")
    val second = run(task, "--configuration-cache")

    assertThat(first.output).contains("BUILD SUCCESSFUL", "Configuration cache entry stored")
    assertThat(second.output).contains("BUILD SUCCESSFUL", "Reusing configuration cache")
    assertThat(first.task("$task-AssertPass-core-jdk")?.outcome).isEqualTo(SUCCESS)
    assertThat(second.output).doesNotContain("Muzzle deferring", "Muzzle retaining")
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
