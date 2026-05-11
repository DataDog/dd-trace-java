package datadog.gradle.plugin.version

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TracerVersionIntegrationTest {

  @Test
  fun `should use default version when not under a git clone`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("0.1.0-SNAPSHOT")
  }

  @Test
  fun `should use default version when no git tags`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("0.1.0-SNAPSHOT") {
      fixture.initGitRepo()
    }
  }

  @Test
  fun `should ignore dirtiness when no git tags`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("0.1.0-SNAPSHOT") {
      fixture.initGitRepo()
      File(projectDir, "settings.gradle.kts").appendText("\n// uncommitted change this file, ")
    }
  }

  @Test
  fun `should use default version when unmatching git tags`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("0.1.0-SNAPSHOT") {
      fixture.initGitRepo()
      fixture.exec("git", "tag", "something1.40.1", "-m", "Not our tag")
    }
  }

  @Test
  fun `should use exact version when on tag`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("1.52.0") {
      fixture.initGitRepo()
      fixture.exec("git", "tag", "v1.52.0", "-m", "")
    }
  }

  @Test
  fun `should increment minor and mark dirtiness`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("1.53.0-SNAPSHOT-DIRTY") {
      File(projectDir, "gradle.properties").writeText("tracerVersion.dirtiness=true")
      fixture.initGitRepo()
      fixture.exec("git", "tag", "v1.52.0", "-m", "")
      File(projectDir, "settings.gradle.kts").appendText("\n// uncommitted change this file, ")
    }
  }

  @Test
  fun `should increment minor with added commits after version tag`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("1.53.0-SNAPSHOT") {
      fixture.initGitRepo()
      fixture.exec("git", "tag", "v1.52.0", "-m", "")
      File(projectDir, "settings.gradle.kts").appendText("\n// Committed change this file, ")
      fixture.exec("git", "commit", "-am", "Another commit")
    }
  }

  @Test
  fun `should increment minor with snapshot and dirtiness with added commits after version tag and dirty`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("1.53.0-SNAPSHOT-DIRTY") {
      File(projectDir, "gradle.properties").writeText("tracerVersion.dirtiness=true")
      fixture.initGitRepo()
      fixture.exec("git", "tag", "v1.52.0", "-m", "")
      val settingsFile = File(projectDir, "settings.gradle.kts")
      settingsFile.appendText("\n// uncommitted change ")
      fixture.exec("git", "commit", "-am", "Another commit")
      settingsFile.appendText("\n// An uncommitted modification")
    }
  }

  @Test
  fun `should increment patch on release branch and no patch release tag`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("1.52.1-SNAPSHOT") {
      fixture.initGitRepo()
      fixture.exec("git", "tag", "v1.52.0", "-m", "")
      File(projectDir, "settings.gradle.kts").appendText("\n// Committed change ")
      fixture.exec("git", "commit", "-am", "Another commit")
      fixture.exec("git", "switch", "-c", "release/v1.52.x")
    }
  }

  @Test
  fun `should increment patch on release branch and with previous patch release tag`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("1.52.2-SNAPSHOT") {
      fixture.initGitRepo()
      fixture.exec("git", "tag", "v1.52.0", "-m", "")
      fixture.exec("git", "switch", "-c", "release/v1.52.x")
      val settingsFile = File(projectDir, "settings.gradle.kts")
      settingsFile.appendText("\n// Committed change ")
      fixture.exec("git", "commit", "-am", "Another commit")
      fixture.exec("git", "tag", "v1.52.1", "-m", "")
      settingsFile.appendText("\n// Another committed change ")
      fixture.exec("git", "commit", "-am", "Another commit")
    }
  }

  @Test
  fun `should compute version on worktrees`(@TempDir projectDir: File, @TempDir workTreeDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertTracerVersion("1.53.0-SNAPSHOT", workingDirectory = workTreeDir) {
      fixture.initGitRepo()
      fixture.exec("git", "tag", "v1.52.0", "-m", "")
      fixture.exec("git", "commit", "-m", "Initial commit", "--allow-empty")
      fixture.exec("git", "worktree", "add", workTreeDir.absolutePath)
      File(workTreeDir, "settings.gradle.kts").appendText("\n// Committed change this file, ")
      fixture.exec(workTreeDir, "git", "commit", "-am", "Another commit")
    }
  }
}
