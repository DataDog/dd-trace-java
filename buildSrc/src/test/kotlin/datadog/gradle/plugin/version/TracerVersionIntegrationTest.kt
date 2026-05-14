package datadog.gradle.plugin.version

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TracerVersionIntegrationTest {

  @Test
  fun `should use default version when not under a git clone`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(expectedVersion = "0.1.0-SNAPSHOT")
  }

  @Test
  fun `should use default version when no git tags`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(
      expectedVersion = "0.1.0-SNAPSHOT",
      beforeGradle = {
        fixture.initGitRepo()
      },
    )
  }

  @Test
  fun `should ignore dirtiness when no git tags`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(
      expectedVersion = "0.1.0-SNAPSHOT",
      beforeGradle = {
        fixture.initGitRepo()
        settingsFile.appendText("\n// uncommitted change this file, ")
      },
    )
  }

  @Test
  fun `should use default version when unmatching git tags`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(
      expectedVersion = "0.1.0-SNAPSHOT",
      beforeGradle = {
        fixture.initGitRepo()
        fixture.exec("git", "tag", "something1.40.1", "-m", "Not our tag")
      },
    )
  }

  @Test
  fun `should use exact version when on tag`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(
      expectedVersion = "1.52.0",
      beforeGradle = {
        fixture.initGitRepo()
        fixture.exec("git", "tag", "v1.52.0", "-m", "")
      },
    )
  }

  @Test
  fun `should increment minor and mark dirtiness`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(
      expectedVersion = "1.53.0-SNAPSHOT-DIRTY",
      beforeGradle = {
        gradlePropertiesFile.writeText("tracerVersion.dirtiness=true")
        fixture.initGitRepo()
        fixture.exec("git", "tag", "v1.52.0", "-m", "")
        settingsFile.appendText("\n// uncommitted change this file, ")
      },
    )
  }

  @Test
  fun `should increment minor with added commits after version tag`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(
      expectedVersion = "1.53.0-SNAPSHOT",
      beforeGradle = {
        fixture.initGitRepo()
        fixture.exec("git", "tag", "v1.52.0", "-m", "")
        settingsFile.appendText("\n// Committed change this file, ")
        fixture.exec("git", "commit", "-am", "Another commit")
      },
    )
  }

  @Test
  fun `should increment minor with snapshot and dirtiness with added commits after version tag and dirty`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(
      expectedVersion = "1.53.0-SNAPSHOT-DIRTY",
      beforeGradle = {
        gradlePropertiesFile.writeText("tracerVersion.dirtiness=true")
        fixture.initGitRepo()
        fixture.exec("git", "tag", "v1.52.0", "-m", "")
        val settings = settingsFile
        settings.appendText("\n// uncommitted change ")
        fixture.exec("git", "commit", "-am", "Another commit")
        settings.appendText("\n// An uncommitted modification")
      },
    )
  }

  @Test
  fun `should increment patch on release branch and no patch release tag`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(
      expectedVersion = "1.52.1-SNAPSHOT",
      beforeGradle = {
        fixture.initGitRepo()
        fixture.exec("git", "tag", "v1.52.0", "-m", "")
        settingsFile.appendText("\n// Committed change ")
        fixture.exec("git", "commit", "-am", "Another commit")
        fixture.exec("git", "switch", "-c", "release/v1.52.x")
      },
    )
  }

  @Test
  fun `should increment patch on release branch and with previous patch release tag`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(
      expectedVersion = "1.52.2-SNAPSHOT",
      beforeGradle = {
        fixture.initGitRepo()
        fixture.exec("git", "tag", "v1.52.0", "-m", "")
        fixture.exec("git", "switch", "-c", "release/v1.52.x")
        val settings = settingsFile
        settings.appendText("\n// Committed change ")
        fixture.exec("git", "commit", "-am", "Another commit")
        fixture.exec("git", "tag", "v1.52.1", "-m", "")
        settings.appendText("\n// Another committed change ")
        fixture.exec("git", "commit", "-am", "Another commit")
      },
    )
  }

  @Test
  fun `should compute version on worktrees`(@TempDir projectDir: File, @TempDir workTreeDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion(
      expectedVersion = "1.53.0-SNAPSHOT",
      workingDirectory = workTreeDir,
      beforeGradle = {
        fixture.initGitRepo()
        fixture.exec("git", "tag", "v1.52.0", "-m", "")
        fixture.exec("git", "commit", "-m", "Initial commit", "--allow-empty")
        fixture.exec("git", "worktree", "add", workTreeDir.absolutePath)
        File(workTreeDir, "settings.gradle.kts").appendText("\n// Committed change this file, ")
        fixture.exec(workTreeDir, "git", "commit", "-am", "Another commit")
      },
    )
  }

  private fun VersionPluginsFixture.assertTracerVersion(
    expectedVersion: String,
    workingDirectory: File? = null,
    beforeGradle: VersionPluginsFixture.() -> Unit = {},
  ) {
    settingsFile.writeText("""rootProject.name = "test-project"""")
    projectBuildFile.writeText(
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

    val buildResult = if (workingDirectory == null) {
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

    assertThat(buildResult.output.lines().first()).isEqualTo(expectedVersion)
  }
}
