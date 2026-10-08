package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.MavenRepoFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class MuzzleDependencyAgeFunctionalTest : MuzzlePluginTestFixture() {
  private lateinit var fixture: MavenRepoFixture
  private val task = ":dd-java-agent:instrumentation:demo:muzzle"
  private val assertionPrefix = "$task-AssertPass-com.example.test-demo-lib-"

  @BeforeEach
  fun setup() {
    fixture = createMavenRepoFixture()
    writeNoopScanPlugin()
  }

  @Test
  fun `default cooldown selects eligible versions across modules`() {
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.1.0"), publishedAt = Instant.now())
    val script = libraryProject(fixture)
    writeProject(script)
    addSubproject("dd-java-agent:instrumentation:other", script)

    val result = runMuzzle(requestedTask = "muzzle", hours = null)

    assertThat(result.output).contains("BUILD SUCCESSFUL", "48h cooldown", "com.example.test:demo-lib:1.1.0")
    for (project in listOf("demo", "other")) {
      val prefix = ":dd-java-agent:instrumentation:$project:muzzle-AssertPass-com.example.test-demo-lib-"
      assertThat(result.task("${prefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
      assertThat(result.task("${prefix}1.1.0")).isNull()
    }
  }

  @Test
  fun `checks the dependency when its publication age cannot be verified`() {
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    // File URLs omit Last-Modified when the modification time is zero.
    val pom = fixture.repoDir.resolve("com/example/test/demo-lib/1.0.0/demo-lib-1.0.0.pom")
    check(pom.setLastModified(0L))
    writeProject(libraryProject(fixture))

    val result = runMuzzle()

    assertThat(result.output).contains("BUILD SUCCESSFUL", "Muzzle retaining com.example.test:demo-lib:1.0.0", "cannot verify publication age")
    assertThat(result.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `zero property overrides the environment and checks fresh releases`() {
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"), publishedAt = Instant.now())
    writeProject(libraryProject(fixture))

    val result = runMuzzle("-PmuzzleMinDependencyAgeHours=0", hours = "96")

    assertThat(result.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.output).doesNotContain("Muzzle deferring")
  }

  @Test
  fun `reused daemon reads cooldown configuration from each build`() {
    fixture.publishVersions(
      "com.example.test",
      "demo-lib",
      listOf("1.0.0"),
      publishedAt = Instant.now().minusSeconds(72 * 3600L)
    )
    writeProject(libraryProject(fixture))

    val deferred = runMuzzle(expectFailure = true, hours = "96")
    assertThat(deferred.output).contains("No eligible muzzle artifacts", "96h publication cooldown")

    val eligible = runMuzzle()
    assertThat(eligible.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `cached graphs revalidate newly eligible releases`(incompatible: Boolean) {
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.1.0"), publishedAt = Instant.now())
    writeProject(libraryProject(fixture))
    if (incompatible) {
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
    }

    val first = runMuzzle("--configuration-cache")
    assertThat(first.output).contains("BUILD SUCCESSFUL", "Configuration cache entry stored")
    assertThat(first.task("${assertionPrefix}1.1.0")).isNull()

    val unchanged = runMuzzle("--configuration-cache")
    assertThat(unchanged.output).contains("BUILD SUCCESSFUL", "Reusing configuration cache")
    assertThat(unchanged.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(UP_TO_DATE)
    assertThat(unchanged.task("${assertionPrefix}1.1.0")).isNull()

    val pom = fixture.repoDir.resolve("com/example/test/demo-lib/1.1.0/demo-lib-1.1.0.pom")
    check(pom.setLastModified(Instant.now().minusSeconds(72 * 3600L).toEpochMilli()))

    val second = runMuzzle("--configuration-cache", expectFailure = incompatible)
    assertThat(second.output).doesNotContain("Reusing configuration cache")
      .contains(if (incompatible) "Muzzle validation failed" else "BUILD SUCCESSFUL")
    assertThat(second.task("${assertionPrefix}1.1.0")?.outcome).isEqualTo(if (incompatible) FAILED else SUCCESS)
    if (incompatible) {
      assertThat(resultFile("muzzle-AssertPass-com.example.test-demo-lib-1.1.0").toFile().readText())
        .contains("Newly eligible version is incompatible")
    }
  }

  @Test
  fun `DSL cooldown overrides lazily configured property and environment defaults`() {
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"), publishedAt = Instant.now())
    writeProject(libraryProject(fixture) + "\nmuzzle { minimumDependencyAgeHours.set(0) }")

    val result = runMuzzle("-PmuzzleMinDependencyAgeHours=invalid", hours = "invalid")

    assertThat(result.output).contains("BUILD SUCCESSFUL").doesNotContain("Muzzle deferring")
    assertThat(result.task("${assertionPrefix}1.0.0")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `each module may override its cooldown without changing other modules`() {
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    fixture.publishVersions("com.example.test", "demo-lib", listOf("1.1.0"), publishedAt = Instant.now())
    writeProject(libraryProject(fixture) + "\nmuzzle { minimumDependencyAgeHours.set(0) }")
    addSubproject("dd-java-agent:instrumentation:other", libraryProject(fixture))

    val result = runMuzzle(requestedTask = "muzzle")

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.task("${assertionPrefix}1.1.0")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:other:muzzle-AssertPass-com.example.test-demo-lib-1.1.0")).isNull()
  }

  @ParameterizedTest
  @ValueSource(strings = ["compileMuzzle", "compileJava"])
  fun `compilation does not resolve versions or cooldown configuration`(compilationTask: String) {
    writeProject(libraryProject(fixture).replace("demo-lib", "absent-lib"))

    val result = runMuzzle("--configuration-cache", requestedTask = ":dd-java-agent:instrumentation:demo:$compilationTask", hours = "invalid")

    assertThat(result.output).contains("BUILD SUCCESSFUL", "Configuration cache entry stored")
      .doesNotContain("Muzzle retaining", "Muzzle deferring", "Muzzle version range resolution failed")
  }

  @Test
  fun `zero cooldown and large sampled ranges reuse an unchanged configuration cache`() {
    fixture.publishVersions("com.example.test", "demo-lib", (0..49).map { "1.$it.0" }, publishedAt = Instant.now())
    writeProject(libraryProject(fixture) + "\nmuzzle { minimumDependencyAgeHours.set(0) }")

    val first = runMuzzle("--configuration-cache")
    val second = runMuzzle("--configuration-cache")

    assertThat(first.output).contains("BUILD SUCCESSFUL", "Configuration cache entry stored")
    assertThat(second.output).contains("BUILD SUCCESSFUL", "Reusing configuration cache")
    assertThat(first.tasks.filter { it.path.startsWith(assertionPrefix) }).hasSize(24)
    assertThat(second.tasks.filter { it.path.startsWith(assertionPrefix) }.map { it.path })
      .containsExactlyElementsOf(first.tasks.filter { it.path.startsWith(assertionPrefix) }.map { it.path })
  }

  @Test
  fun `invalid cooldown fails before dependency resolution`() {
    writeProject(libraryProject(fixture))

    val result = runMuzzle("-PmuzzleMinDependencyAgeHours=-1", expectFailure = true)

    assertThat(result.output).contains("must be a non-negative integer")
      .doesNotContain("Muzzle version range resolution failed")
  }

  private fun runMuzzle(
    vararg arguments: String,
    requestedTask: String = task,
    hours: String? = "48",
    expectFailure: Boolean = false
  ) = run(
    requestedTask, *arguments, expectFailure = expectFailure,
    env = mapOf("MAVEN_REPOSITORY_PROXY" to fixture.repoUrl) +
      if (hours == null) emptyMap() else mapOf("MIN_DEPENDENCY_AGE_HOURS" to hours),
    unsetEnv = if (hours == null) setOf("MIN_DEPENDENCY_AGE_HOURS") else emptySet()
  )

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
