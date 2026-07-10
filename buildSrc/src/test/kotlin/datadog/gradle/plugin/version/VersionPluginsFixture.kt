package datadog.gradle.plugin.version

import datadog.gradle.plugin.GradleFixture
import java.io.File
import java.io.IOException

open class VersionPluginsFixture : GradleFixture() {
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

  val generatedVersionFile: File get() = file("build/generated/version/my-lib.version")

  val builtResourceVersionFile: File get() = file("build/resources/main/my-lib.version")
}
