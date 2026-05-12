package datadog.gradle.plugin.version

import datadog.gradle.plugin.GradleFixture
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

  val projectBuildFile = file("build.gradle.kts")

  val gradlePropertiesFile = file("gradle.properties")

  val settingsFile = file("settings.gradle.kts")

  val generatedVersionFile = file("build/generated/version/my-lib.version", false)

  val builtResourceVersionFile = file("build/resources/main/my-lib.version", false)
}
