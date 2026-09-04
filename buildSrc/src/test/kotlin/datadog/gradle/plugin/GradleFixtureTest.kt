package datadog.gradle.plugin

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test

class GradleFixtureTest : GradleFixture() {

  private companion object {
    const val MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2"
    const val PLUGIN_PORTAL = "https://plugins.gradle.org/m2"
  }

  @Test
  fun `TestKit build routes Maven Central through configured proxy`() {
    val proxyRepository = createMavenRepoFixture()
    proxyRepository.publishVersions("com.example", "proxy-only", listOf("1.0.0"))
    writeRootProject(
      """
      plugins {
        id("java")
      }

      repositories {
        mavenCentral()
      }

      dependencies {
        implementation("com.example:proxy-only:1.0.0")
      }
      """
    )
    writeJavaSource("Example", "public class Example {}")

    val result = run(
      "compileJava",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to proxyRepository.repoUrl),
    )

    assertThat(result.task(":compileJava")?.outcome).isEqualTo(SUCCESS)
  }

  /**
   * Contributors without access to the internal Depot mirror run with no proxy configured.
   * The init script must then be completely inert. Assert on the repository URLs of every
   * container it touches rather than resolving anything, so the check stays hermetic.
   */
  @Test
  fun `TestKit build leaves public repositories untouched when no proxy is configured`() {
    writeSettings(
      """
      import org.gradle.api.artifacts.repositories.MavenArtifactRepository

      pluginManagement {
        repositories {
          gradlePluginPortal()
        }
      }
      dependencyResolutionManagement {
        repositories {
          mavenCentral()
        }
      }
      rootProject.name = "no-proxy"

      gradle.settingsEvaluated {
        (pluginManagement.repositories + dependencyResolutionManagement.repositories)
          .filterIsInstance<MavenArtifactRepository>()
          .forEach { println("REPOSITORY=" + it.url) }
      }
      """
    )
    writeRootProject(
      """
      import org.gradle.api.artifacts.repositories.MavenArtifactRepository

      buildscript {
        repositories {
          mavenCentral()
        }
      }

      plugins {
        id("java")
      }

      tasks.register("printRepositories") {
        val urls = buildscript.repositories
          .filterIsInstance<MavenArtifactRepository>()
          .map { it.url.toString() }
        doLast { urls.forEach { println("REPOSITORY=" + it) } }
      }
      """
    )

    val result = run(
      "printRepositories",
      unsetEnv = setOf("MAVEN_REPOSITORY_PROXY", "GRADLE_PLUGIN_PROXY"),
    )

    val repositories = result.output.lines()
      .filter { it.startsWith("REPOSITORY=") }
      .map { it.removePrefix("REPOSITORY=") }
    assertThat(repositories)
      .withFailMessage("Expected repositories to be printed, output was:\n%s", result.output)
      .isNotEmpty()
    assertThat(repositories)
      .withFailMessage("No proxy is configured, so nothing may be rewritten, got %s", repositories)
      .allMatch { it.startsWith(MAVEN_CENTRAL) || it.startsWith(PLUGIN_PORTAL) }
    assertThat(repositories).anyMatch { it.startsWith(MAVEN_CENTRAL) }
    assertThat(repositories).anyMatch { it.startsWith(PLUGIN_PORTAL) }
  }

  @Test
  fun `TestKit build routes settings-level Maven Central through configured proxy`() {
    val proxyRepository = createMavenRepoFixture()
    proxyRepository.publishVersions("com.example", "settings-proxy-only", listOf("1.0.0"))
    writeSettings(
      """
      dependencyResolutionManagement {
        repositories {
          mavenCentral()
        }
      }
      """
    )
    writeRootProject(
      """
      plugins {
        id("java")
      }

      dependencies {
        implementation("com.example:settings-proxy-only:1.0.0")
      }
      """
    )
    writeJavaSource("Example", "public class Example {}")

    val result = run(
      "compileJava",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to proxyRepository.repoUrl),
    )

    assertThat(result.task(":compileJava")?.outcome).isEqualTo(SUCCESS)
  }
}
