package datadog.gradle.plugin.version

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class TracerVersionIntegrationTest {

  @Test
  fun `should use default version when not under a git clone`(@TempDir projectDir: File) {
    assertTracerVersion(projectDir, "0.1.0-SNAPSHOT")
  }

  @Test
  fun `should use default version when no git tags`(@TempDir projectDir: File) {
    assertTracerVersion(
      projectDir,
      "0.1.0-SNAPSHOT",
      beforeGradle = {
        exec(projectDir, "git", "init", "--initial-branch", "main")
        exec(projectDir, "git", "config", "user.email", "test@datadoghq.com")
        exec(projectDir, "git", "config", "user.name", "Test")
        exec(projectDir, "git", "add", "-A")
        exec(projectDir, "git", "commit", "-m", "A commit")
      }
    )
  }

  @Test
  fun `should ignore dirtiness when no git tags`(@TempDir projectDir: File) {
    assertTracerVersion(
      projectDir,
      "0.1.0-SNAPSHOT",
      beforeGradle = {
        exec(projectDir, "git", "init", "--initial-branch", "main")
        exec(projectDir, "git", "config", "user.email", "test@datadoghq.com")
        exec(projectDir, "git", "config", "user.name", "Test")
        exec(projectDir, "git", "add", "-A")
        exec(projectDir, "git", "commit", "-m", "A commit")

        File(projectDir, "settings.gradle.kts").appendText("""
          
          // uncommitted change this file, 
        """.trimIndent())
      }
    )
  }

  @Test
  fun `should use default version when unmatching git tags`(@TempDir projectDir: File) {
    assertTracerVersion(
      projectDir,
      "0.1.0-SNAPSHOT",
      beforeGradle = {
        exec(projectDir, "git", "init", "--initial-branch", "main")
        exec(projectDir, "git", "config", "user.email", "test@datadoghq.com")
        exec(projectDir, "git", "config", "user.name", "Test")
        exec(projectDir, "git", "add", "-A")
        exec(projectDir, "git", "commit", "-m", "A commit")
        exec(projectDir, "git", "tag", "something1.40.1", "-m", "Not our tag")
      }
    )
  }

  @Test
  fun `should use exact version when on tag`(@TempDir projectDir: File) {
    assertTracerVersion(
      projectDir,
      "1.52.0",
      beforeGradle = {
        exec(projectDir, "git", "init", "--initial-branch", "main")
        exec(projectDir, "git", "config", "user.email", "test@datadoghq.com")
        exec(projectDir, "git", "config", "user.name", "Test")
        exec(projectDir, "git", "add", "-A")
        exec(projectDir, "git", "commit", "-m", "A commit")
        exec(projectDir, "git", "tag", "v1.52.0", "-m", "")
      }
    )
  }

  @Test
  fun `should increment minor and mark dirtiness`(@TempDir projectDir: File) {
    assertTracerVersion(
      projectDir,
      "1.53.0-SNAPSHOT-DIRTY",
      beforeGradle = {
        println("Setting up git repository in $projectDir")
        File(projectDir, "gradle.properties").writeText(
          """
          tracerVersion.dirtiness=true
          """.trimIndent()
        )

        exec(projectDir, "git", "init", "--initial-branch", "main")
        exec(projectDir, "git", "config", "user.email", "test@datadoghq.com")
        exec(projectDir, "git", "config", "user.name", "Test")
        exec(projectDir, "git", "add", "-A")
        exec(projectDir, "git", "commit", "-m", "A commit")
        exec(projectDir, "git", "tag", "v1.52.0", "-m", "")

        File(projectDir, "settings.gradle.kts").appendText("""
          
          // uncommitted change this file, 
        """.trimIndent())
      }
    )
  }

  @Test
  fun `should increment minor with added commits after version tag`(@TempDir projectDir: File) {
    assertTracerVersion(
      projectDir,
      "1.53.0-SNAPSHOT",
      beforeGradle = {
        exec(projectDir, "git", "init", "--initial-branch", "main")
        exec(projectDir, "git", "config", "user.email", "test@datadoghq.com")
        exec(projectDir, "git", "config", "user.name", "Test")
        exec(projectDir, "git", "add", "-A")
        exec(projectDir, "git", "commit", "-m", "A commit")
        exec(projectDir, "git", "tag", "v1.52.0", "-m", "")

        File(projectDir, "settings.gradle.kts").appendText(
          """
          
          // Committed change this file, 
        """.trimIndent()
        )
        exec(projectDir, "git", "commit", "-am", "Another commit")
      }
    )
  }

  @Test
  fun `should increment minor with snapshot and dirtiness with added commits after version tag and dirty`(@TempDir projectDir: File) {
    assertTracerVersion(
      projectDir,
      "1.53.0-SNAPSHOT-DIRTY",
      beforeGradle = {
        File(projectDir, "gradle.properties").writeText(
          """
          tracerVersion.dirtiness=true
          """.trimIndent()
        )

        exec(projectDir, "git", "init", "--initial-branch", "main")
        exec(projectDir, "git", "config", "user.email", "test@datadoghq.com")
        exec(projectDir, "git", "config", "user.name", "Test")
        exec(projectDir, "git", "add", "-A")
        exec(projectDir, "git", "commit", "-m", "A commit")
        exec(projectDir, "git", "tag", "v1.52.0", "-m", "")

        val settingsFile = File(projectDir, "settings.gradle.kts")
        settingsFile.appendText("""
          
          // uncommitted change 
        """.trimIndent())

        exec(projectDir, "git", "commit", "-am", "Another commit")

        settingsFile.appendText("""
          // An uncommitted modification
        """.trimIndent())
      }
    )
  }

  @Test
  fun `should increment patch on release branch and no patch release tag`(@TempDir projectDir: File) {
    assertTracerVersion(
      projectDir,
      "1.52.1-SNAPSHOT",
      beforeGradle = {
        exec(projectDir, "git", "init", "--initial-branch", "main")
        exec(projectDir, "git", "config", "user.email", "test@datadoghq.com")
        exec(projectDir, "git", "config", "user.name", "Test")
        exec(projectDir, "git", "add", "-A")
        exec(projectDir, "git", "commit", "-m", "A commit")
        exec(projectDir, "git", "tag", "v1.52.0", "-m", "")

        val settingsFile = File(projectDir, "settings.gradle.kts")
        settingsFile.appendText("""
          
          // Committed change 
        """.trimIndent())

        exec(projectDir, "git", "commit", "-am", "Another commit")
        exec(projectDir, "git", "switch", "-c", "release/v1.52.x")
      }
    )
  }

  @Test
  fun `should increment patch on release branch and with previous patch release tag`(@TempDir projectDir: File) {
    assertTracerVersion(
      projectDir,
      "1.52.2-SNAPSHOT",
      beforeGradle = {
        exec(projectDir, "git", "init", "--initial-branch", "main")
        exec(projectDir, "git", "config", "user.email", "test@datadoghq.com")
        exec(projectDir, "git", "config", "user.name", "Test")
        exec(projectDir, "git", "add", "-A")
        exec(projectDir, "git", "commit", "-m", "A commit")
        exec(projectDir, "git", "tag", "v1.52.0", "-m", "")
        exec(projectDir, "git", "switch", "-c", "release/v1.52.x")

        val settingsFile = File(projectDir, "settings.gradle.kts")
        settingsFile.appendText("""
          
          // Committed change 
        """.trimIndent())
        exec(projectDir, "git", "commit", "-am", "Another commit")
        exec(projectDir, "git", "tag", "v1.52.1", "-m", "")

        settingsFile.appendText("""
          
          // Another committed change 
        """.trimIndent())
        exec(projectDir, "git", "commit", "-am", "Another commit")
      }
    )
  }

  @Test
  fun `should compute version on worktrees`(@TempDir projectDir: File, @TempDir workTreeDir: File) {
    assertTracerVersion(
      projectDir,
      "1.53.0-SNAPSHOT",
      beforeGradle = {
        exec(projectDir, "git", "init", "--initial-branch", "main")
        exec(projectDir, "git", "config", "user.email", "test@datadoghq.com")
        exec(projectDir, "git", "config", "user.name", "Test")
        exec(projectDir, "git", "add", "-A")
        exec(projectDir, "git", "commit", "-m", "A commit")
        exec(projectDir, "git", "tag", "v1.52.0", "-m", "")

        exec(projectDir, "git", "commit", "-m", "Initial commit", "--allow-empty")
        exec(projectDir, "git", "worktree", "add", workTreeDir.absolutePath)
        // happening on the worktree
        File(workTreeDir, "settings.gradle.kts").appendText(
          """
          
          // Committed change this file, 
        """.trimIndent()
        )
        exec(workTreeDir, "git", "commit", "-am", "Another commit")
      },
      workingDirectory = workTreeDir
    )
  }

  private fun assertTracerVersion(
    projectDir: File,
    expectedVersion: String,
    beforeGradle: () -> Unit = {},
    workingDirectory: File = projectDir,
  ) {
    File(projectDir, "settings.gradle.kts").writeText(
      """
      rootProject.name = "test-project"
      """.trimIndent()
    )
    File(projectDir, "build.gradle.kts").writeText(
      """
      plugins {
        id("datadog.tracer-version")
      }
      
      tasks.register("printVersion") {
        logger.quiet(project.version.toString())
      }

      group = "datadog.tracer.version.test"
      """.trimIndent()
    )

    beforeGradle()

    val buildResult = GradleRunner.create()
      .forwardOutput()
      // .withGradleVersion(gradleVersion)  // Use current gradle version
      .withPluginClasspath()
      .withArguments("printVersion", "--quiet")
      .withProjectDir(workingDirectory)
      // .withDebug(true)
      .build()

    assertEquals(expectedVersion, buildResult.output.lines().first())
  }

  private fun exec(workingDirectory: File, vararg args: String) {
    val exitCode = ProcessBuilder()
      .command(*args)
      .directory(workingDirectory)
      .inheritIO()
      .start()
      .waitFor()

    if (exitCode != 0) {
      throw IOException(String.format("Process failed: %s Exit code %d", args.joinToString(" "), exitCode))
    }
  }
}
