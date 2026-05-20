package datadog.gradle.plugin.version

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TracerVersionIntegrationTest : VersionPluginsFixture() {

  @Test
  fun `should use default version when not under a git clone`() {
    assertTracerVersion(expectedVersion = "0.1.0-SNAPSHOT")
  }

  @Test
  fun `should use default version when no git tags`() {
    assertTracerVersion(
      expectedVersion = "0.1.0-SNAPSHOT",
      beforeGradle = {
        initGitRepo()
      },
    )
  }

  @Test
  fun `should ignore dirtiness when no git tags`() {
    assertTracerVersion(
      expectedVersion = "0.1.0-SNAPSHOT",
      beforeGradle = {
        initGitRepo()
        writeSettings("// uncommitted change this file, ", append = true)
      },
    )
  }

  @Test
  fun `should use default version when unmatching git tags`() {
    assertTracerVersion(
      expectedVersion = "0.1.0-SNAPSHOT",
      beforeGradle = {
        initGitRepo()
        exec("git", "tag", "something1.40.1", "-m", "Not our tag")
      },
    )
  }

  @Test
  fun `should use exact version when on tag`() {
    assertTracerVersion(
      expectedVersion = "1.52.0",
      beforeGradle = {
        initGitRepo()
        exec("git", "tag", "v1.52.0", "-m", "")
      },
    )
  }

  @Test
  fun `should increment minor and mark dirtiness`() {
    assertTracerVersion(
      expectedVersion = "1.53.0-SNAPSHOT-DIRTY",
      beforeGradle = {
        writeGradleProperties("tracerVersion.dirtiness=true")
        initGitRepo()
        exec("git", "tag", "v1.52.0", "-m", "")
        writeSettings("// uncommitted change this file, ", append = true)
      },
    )
  }

  @Test
  fun `should increment minor with added commits after version tag`() {
    assertTracerVersion(
      expectedVersion = "1.53.0-SNAPSHOT",
      beforeGradle = {
        initGitRepo()
        exec("git", "tag", "v1.52.0", "-m", "")
        writeSettings("// Committed change this file, ", append = true)
        exec("git", "commit", "-am", "Another commit")
      },
    )
  }

  @Test
  fun `should increment minor with snapshot and dirtiness with added commits after version tag and dirty`() {
    assertTracerVersion(
      expectedVersion = "1.53.0-SNAPSHOT-DIRTY",
      beforeGradle = {
        writeGradleProperties("tracerVersion.dirtiness=true")
        initGitRepo()
        exec("git", "tag", "v1.52.0", "-m", "")
        writeSettings("// uncommitted change ", append = true)
        exec("git", "commit", "-am", "Another commit")
        writeSettings("// An uncommitted modification", append = true)
      },
    )
  }

  @Test
  fun `should increment patch on release branch and no patch release tag`() {
    assertTracerVersion(
      expectedVersion = "1.52.1-SNAPSHOT",
      beforeGradle = {
        initGitRepo()
        exec("git", "tag", "v1.52.0", "-m", "")
        writeSettings("// Committed change ", append = true)
        exec("git", "commit", "-am", "Another commit")
        exec("git", "switch", "-c", "release/v1.52.x")
      },
    )
  }

  @Test
  fun `should increment patch on release branch and with previous patch release tag`() {
    assertTracerVersion(
      expectedVersion = "1.52.2-SNAPSHOT",
      beforeGradle = {
        initGitRepo()
        exec("git", "tag", "v1.52.0", "-m", "")
        exec("git", "switch", "-c", "release/v1.52.x")
        writeSettings("// Committed change ", append = true)
        exec("git", "commit", "-am", "Another commit")
        exec("git", "tag", "v1.52.1", "-m", "")
        writeSettings("// Another committed change ", append = true)
        exec("git", "commit", "-am", "Another commit")
      },
    )
  }

  @Test
  fun `should compute version on worktrees`(@TempDir workTreeDir: File) {
    assertTracerVersion(
      expectedVersion = "1.53.0-SNAPSHOT",
      workingDirectory = workTreeDir,
      beforeGradle = {
        initGitRepo()
        exec("git", "tag", "v1.52.0", "-m", "")
        exec("git", "commit", "-m", "Initial commit", "--allow-empty")
        exec("git", "worktree", "add", workTreeDir.absolutePath)
        // Write into workTreeDir, not projectDir, so the next commit has changes to pick up.
        File(workTreeDir, "settings.gradle.kts").appendText("\n// Committed change this file, ")
        exec(workTreeDir, "git", "commit", "-am", "Another commit")
      },
    )
  }

  @Test
  fun `should increment minor from merged main version tag on feature branch`() {
    assertTracerVersion(
      expectedVersion = "1.53.0-SNAPSHOT",
      beforeGradle = {
        initGitRepo()
        exec("git", "tag", "v1.50.0", "-m", "")
        exec("git", "switch", "-c", "feature")
        writeFile("feature.txt", "feature")
        exec("git", "add", "feature.txt")
        exec("git", "commit", "-m", "Feature commit")
        exec("git", "switch", "main")
        writeFile("main.txt", "main")
        exec("git", "add", "main.txt")
        exec("git", "commit", "-m", "Main commit")
        exec("git", "tag", "v1.52.0", "-m", "")
        exec("git", "switch", "feature")
        exec("git", "merge", "main", "--no-edit")
      },
    )
  }

  @Test
  fun `should increment patch from first parent on release branch after main merge`() {
    assertTracerVersion(
      expectedVersion = "1.52.1-SNAPSHOT",
      beforeGradle = {
        initGitRepo()
        exec("git", "tag", "v1.52.0", "-m", "")
        exec("git", "switch", "-c", "release/v1.52.x")
        writeFile("release.txt", "release")
        exec("git", "add", "release.txt")
        exec("git", "commit", "-m", "Release commit")
        exec("git", "switch", "main")
        writeFile("main.txt", "main")
        exec("git", "add", "main.txt")
        exec("git", "commit", "-m", "Main commit")
        exec("git", "tag", "v1.53.0", "-m", "")
        exec("git", "switch", "release/v1.52.x")
        exec("git", "merge", "main", "--no-edit")
      },
    )
  }

  private fun assertTracerVersion(
    expectedVersion: String,
    workingDirectory: File = projectDir,
    beforeGradle: VersionPluginsFixture.() -> Unit = {},
  ) {
    writeSettings(
      """
      rootProject.name = "test-project"
      """
    )

    writeRootProject(
      """
      plugins {
        id("dd-trace-java.tracer-version")
      }

      tasks.register("printVersion") {
        logger.quiet(project.version.toString())
      }

      group = "datadog.tracer.version.test"
      """
    )

    beforeGradle()

    val buildResult = run("printVersion", "--quiet", gradleProjectDir = workingDirectory)

    assertThat(buildResult.output.lines().first()).isEqualTo(expectedVersion)
  }
}
