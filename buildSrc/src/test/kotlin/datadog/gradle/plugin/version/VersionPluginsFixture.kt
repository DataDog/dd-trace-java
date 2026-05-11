package datadog.gradle.plugin.version

import datadog.gradle.plugin.GradleFixture
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.io.IOException

internal class VersionPluginsFixture(projectDir: File) : GradleFixture(projectDir) {

  fun exec(workingDirectory: File, vararg args: String) {
    val exitCode = ProcessBuilder()
      .command(*args)
      .directory(workingDirectory)
      .inheritIO()
      .start()
      .waitFor()
    if (exitCode != 0) {
      throw IOException("Process failed: ${args.joinToString(" ")} (exit code $exitCode)")
    }
  }

  fun exec(vararg args: String) = exec(projectDir, *args)

  fun initGitRepo(workingDirectory: File = projectDir) {
    exec(workingDirectory, "git", "init", "--initial-branch", "main")
    exec(workingDirectory, "git", "config", "user.email", "test@datadoghq.com")
    exec(workingDirectory, "git", "config", "user.name", "Test")
    exec(workingDirectory, "git", "add", "-A")
    exec(workingDirectory, "git", "commit", "-m", "A commit")
  }

  fun assertTracerVersion(
    expectedVersion: String,
    workingDirectory: File = projectDir,
    beforeGradle: () -> Unit = {},
  ) {
    file("settings.gradle.kts").writeText("""rootProject.name = "test-project"""")
    file("build.gradle.kts").writeText(
      """
      plugins {
        id("dd-trace-java.tracer-version")
      }

      tasks.register("printVersion") {
        logger.quiet(project.version.toString())
      }

      group = "datadog.tracer.version.test"
      """.trimIndent()
    )

    beforeGradle()

    val buildResult = if (workingDirectory == projectDir) {
      run("printVersion", "--quiet")
    } else {
      // Worktree: Gradle must run from a directory other than projectDir,
      // so use GradleRunner directly instead of GradleFixture.run().
      GradleRunner.create()
        .forwardOutput()
        .withPluginClasspath()
        .withArguments("printVersion", "--quiet")
        .withProjectDir(workingDirectory)
        .build()
    }

    assertEquals(expectedVersion, buildResult.output.lines().first())
  }
}
