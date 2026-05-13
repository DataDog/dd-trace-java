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
        rootProject(
          """

          tasks.named('writeVersionNumberFile', datadog.gradle.plugin.version.WriteVersionFile).configure {
            version.set('9.9.9')
            gitHash.set('deadbeef')
          }
          """
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
        rootProject(
          """

          tasks.named('writeVersionNumberFile', datadog.gradle.plugin.version.WriteVersionFile).configure {
            gitHash.set('abc12345')
          }
          """
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
        rootProject(
          """

          tasks.named('writeVersionNumberFile', datadog.gradle.plugin.version.WriteVersionFile).configure {
            gitHash.set('abc12345')
          }
          """
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
        rootProject(
          """

          tasks.named('writeVersionNumberFile', datadog.gradle.plugin.version.WriteVersionFile).configure {
            gitHash.set('abc12345')
          }
          """
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
        rootProject(
          """

          tasks.named('writeVersionNumberFile', datadog.gradle.plugin.version.WriteVersionFile).configure {
            gitHash.set('abc12345')
          }
          """
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
    settings(
      """
      rootProject.name = 'my-lib'
      """
    )
    rootProject(
      """
      plugins {
        id 'dd-trace-java.version-file'
      }

      version = '1.2.3'
      """
    )
    beforeGradle()

    val buildResult = run(task)
    val taskPath = if (task.startsWith(":")) task else ":$task"

    assertThat(buildResult.task(taskPath)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(generatedVersionFile).exists().isFile()
    assertThat(generatedVersionFile.readText()).matches(expectedContentRegex)
    return buildResult
  }
}
