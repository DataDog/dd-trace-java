package datadog.gradle.plugin.version

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WriteVersionFilePluginTest {

  @Test
  fun `writes version file in version~hash format`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertVersionFile(
      expectedContentRegex = "1\\.2\\.3~[0-9a-f]+",
      beforeGradle = {
        initGitRepo()
      },
    )
  }

  @Test
  fun `version and gitHash properties can be overridden`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertVersionFile(
      expectedContentRegex = "9.9.9~deadbeef",
      beforeGradle = {
        projectBuildFile.appendText(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile").configure {
            version.set("9.9.9")
            gitHash.set("deadbeef")
          }
          """.trimIndent()
        )
      },
    )
  }

  @Test
  fun `task overwrites existing version file`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertVersionFile(
      expectedContentRegex = "1.2.3~abc12345",
      beforeGradle = {
        projectBuildFile.appendText(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile").configure {
            gitHash.set("abc12345")
          }
          """.trimIndent()
        )
        generatedVersionFile.run {
          parentFile.mkdirs()
          writeText("stale-version")
        }
      },
    )
  }

  @Test
  fun `version file generation is wired into main resources`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertVersionFile(
      expectedContentRegex = "1.2.3~abc12345",
      task = "processResources",
      beforeGradle = {
        projectBuildFile.appendText(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile").configure {
            gitHash.set("abc12345")
          }
          """.trimIndent()
        )
      },
    )

    assertThat(fixture.builtResourceVersionFile).exists()
  }

  @Test
  fun `task is up-to-date on second run`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertVersionFile(
      expectedContentRegex = "1.2.3~abc12345",
      beforeGradle = {
        projectBuildFile.appendText(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile").configure {
            gitHash.set("abc12345")
          }
          """.trimIndent()
        )
      },
    )

    val result = fixture.run("writeVersionNumberFile")

    assertThat(result.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `clean deletes version file`(@TempDir projectDir: File) {
    val fixture = VersionPluginsFixture(projectDir)
    fixture.assertVersionFile(
      expectedContentRegex = "1.2.3~abc12345",
      beforeGradle = {
        projectBuildFile.appendText(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile").configure {
            gitHash.set("abc12345")
          }
          """.trimIndent()
        )
      },
    )
    val versionFile = fixture.generatedVersionFile

    val result = fixture.run("clean")

    assertThat(result.task(":clean")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(versionFile).doesNotExist()
  }

  private fun VersionPluginsFixture.assertVersionFile(
    expectedContentRegex: String,
    task: String = ":writeVersionNumberFile",
    beforeGradle: VersionPluginsFixture.() -> Unit = {},
  ): BuildResult {
    settingsFile.writeText("""rootProject.name = "my-lib"""")
    projectBuildFile.writeText(
      """
      plugins {
        id("dd-trace-java.version-file")
      }

      version = "1.2.3"
      """.trimIndent()
    )
    beforeGradle()

    val buildResult = run(task)

    assertThat(buildResult.task(task)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(generatedVersionFile).exists().isFile()
    assertThat(generatedVersionFile.readText()).matches(expectedContentRegex)
    return buildResult
  }
}
