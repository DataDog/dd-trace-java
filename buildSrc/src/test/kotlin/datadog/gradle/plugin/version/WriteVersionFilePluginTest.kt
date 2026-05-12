package datadog.gradle.plugin.version

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WriteVersionFilePluginTest {

  @Test
  fun `writes version file in version~hash format`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    setupProject(projectDir)
    fixture.initGitRepo()

    val result = fixture.run("writeVersionNumberFile")

    assertThat(result.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(projectDir, "build/generated/version/my-lib.version"))
      .exists()
      .content().matches("""1\.2\.3~[0-9a-f]+""")
  }

  @Test
  fun `version and gitHash properties can be overridden`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    setupProject(
      projectDir,
      extraBuildScript = """
        tasks.named("writeVersionNumberFile").configure {
          version.set("9.9.9")
          gitHash.set("deadbeef")
        }
      """,
    )

    val result = fixture.run("writeVersionNumberFile")

    assertThat(result.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(projectDir, "build/generated/version/my-lib.version"))
      .content().isEqualTo("9.9.9~deadbeef")
  }

  @Test
  fun `task overwrites existing version file`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    setupProject(
      projectDir,
      extraBuildScript = """
        tasks.named("writeVersionNumberFile").configure {
          gitHash.set("abc12345")
        }
      """,
    )
    val versionFile = versionFile(projectDir)
    versionFile.parentFile.mkdirs()
    versionFile.writeText("stale-version")

    val result = fixture.run("writeVersionNumberFile")

    assertThat(result.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(versionFile).content().isEqualTo("1.2.3~abc12345")
  }

  @Test
  fun `version file generation is wired into main resources`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    setupProject(
      projectDir,
      extraBuildScript = """
        tasks.named("writeVersionNumberFile").configure {
          gitHash.set("abc12345")
        }
      """,
    )

    fixture.run("processResources")

    assertThat(File(projectDir, "build/resources/main/my-lib.version")).exists()
  }

  @Test
  fun `task is up-to-date on second run`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    setupProject(
      projectDir,
      extraBuildScript = """
        tasks.named("writeVersionNumberFile").configure {
          gitHash.set("abc12345")
        }
      """,
    )
    fixture.run("writeVersionNumberFile")

    val result = fixture.run("writeVersionNumberFile")

    assertThat(result.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `clean deletes version file`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    setupProject(
      projectDir,
      extraBuildScript = """
        tasks.named("writeVersionNumberFile").configure {
          gitHash.set("abc12345")
        }
      """,
    )
    val versionFile = versionFile(projectDir)
    fixture.run("writeVersionNumberFile")
    assertThat(versionFile).exists()

    val result = fixture.run("clean")

    assertThat(result.task(":clean")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(versionFile).doesNotExist()
  }

  private fun versionFile(projectDir: File): File =
    File(projectDir, "build/generated/version/my-lib.version")

  private fun setupProject(projectDir: File, extraBuildScript: String = "") {
    File(projectDir, "settings.gradle").writeText("rootProject.name = 'my-lib'")
    File(projectDir, "build.gradle").writeText(
      """
      plugins {
        id 'dd-trace-java.version-file'
      }
      version = '1.2.3'
      $extraBuildScript
      """.trimIndent()
    )
  }
}
