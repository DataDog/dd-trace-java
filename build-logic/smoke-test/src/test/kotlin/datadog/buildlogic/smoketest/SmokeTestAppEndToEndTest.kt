package datadog.buildlogic.smoketest

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * End-to-end tests that drive the plugin through the Gradle Test Kit and a temporary,
 * self-contained Kotlin-DSL test project. The inner "smoke-test application" is itself a
 * minimal Kotlin-DSL Gradle build; the outer build wires it through the `smokeTestApp` DSL.
 *
 * These tests are slow (each test spins up a Gradle daemon) but they are the only way to
 * exercise the Tooling API path end-to-end.
 */
class SmokeTestAppEndToEndTest {

  @TempDir
  lateinit var projectDir: Path

  private val outerSettings get() = projectDir.resolve("settings.gradle.kts").toFile()
  private val outerBuild get() = projectDir.resolve("build.gradle.kts").toFile()
  private val applicationDir get() = projectDir.resolve("application").toFile()

  @BeforeEach
  fun setUp() {
    applicationDir.mkdirs()
  }

  @Test
  fun `application block registers a NestedGradleBuild task with the configured name`() {
    writeOuterSettings()
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("packageApp")
          artifactPath.set("libs/test.jar")
          sysProperty.set("test.path")
        }
      }
      """.trimIndent(),
    )

    val result = runner("tasks", "--all").build()

    assertThat(result.output).contains("packageApp")
  }

  @Test
  fun `nested build produces the configured artifact`() {
    writeOuterSettings()
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        application {
          taskName.set("buildJar")
          artifactPath.set("libs/sample.jar")
          sysProperty.set("sample.path")
        }
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      tasks.register<Jar>("buildJar") {
        archiveFileName.set("sample.jar")
        from(file("src"))
      }
      """.trimIndent(),
    )
    File(applicationDir, "src").mkdir()
    File(applicationDir, "src/hello.txt").writeText("hi")

    val result = runner("buildJar").build()

    assertThat(result.task(":buildJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(projectDir.toFile(), "build/application/libs/sample.jar")).exists()
  }

  @Test
  fun `plugin is a no-op when the application block is never called`() {
    writeOuterSettings()
    outerBuild.writeText(
      """
      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      smokeTestApp {
        // No application block, no javaLauncher — should not blow up.
      }
      """.trimIndent(),
    )

    val result = runner("help").build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  @Test
  fun `manual NestedGradleBuild task registration works without the application block`() {
    writeOuterSettings()
    outerBuild.writeText(
      """
      import datadog.buildlogic.smoketest.NestedGradleBuild

      plugins {
        java
        id("dd-trace-java.smoke-test-app")
      }

      tasks.register<NestedGradleBuild>("customBuild") {
        applicationDir.set(layout.projectDirectory.dir("application"))
        applicationBuildDir.set(layout.buildDirectory.dir("application"))
        gradleVersion.set(gradle.gradleVersion)
        javaLauncher.set(
          javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${currentMajorJdk()})) }
        )
        tasksToRun.set(listOf("buildJar"))
      }
      """.trimIndent(),
    )
    writeInnerSettings()
    writeInnerBuild(
      """
      tasks.register<Jar>("buildJar") {
        archiveFileName.set("custom.jar")
        from(file("src"))
      }
      """.trimIndent(),
    )
    File(applicationDir, "src").mkdir()
    File(applicationDir, "src/hello.txt").writeText("hi")

    val result = runner("customBuild").build()

    assertThat(result.task(":customBuild")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  private fun writeOuterSettings() {
    outerSettings.writeText(
      """
      rootProject.name = "smoke-test-app-fixture"
      """.trimIndent(),
    )
  }

  private fun writeInnerSettings() {
    File(applicationDir, "settings.gradle.kts").writeText(
      """
      rootProject.name = "smoke-test-app-fixture-application"
      """.trimIndent(),
    )
  }

  private fun writeInnerBuild(taskBlock: String) {
    File(applicationDir, "build.gradle.kts").writeText(
      """
      plugins {
        java
      }
      if (hasProperty("appBuildDir")) {
        layout.buildDirectory.set(file(property("appBuildDir") as String))
      }
      $taskBlock
      """.trimIndent(),
    )
  }

  private fun runner(vararg args: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir.toFile())
      .withPluginClasspath()
      .withArguments(*args, "--stacktrace")
      .forwardOutput()

  private fun currentMajorJdk(): Int =
    System.getProperty("java.specification.version").let {
      if (it.startsWith("1.")) it.substring(2).toInt() else it.toInt()
    }
}
