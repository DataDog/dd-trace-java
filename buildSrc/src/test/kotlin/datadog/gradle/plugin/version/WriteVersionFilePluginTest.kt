package datadog.gradle.plugin.version

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

class WriteVersionFilePluginTest : VersionPluginsFixture() {

  @Test
  fun `writes version file in version~hash format`() {
    assertVersionFile(
      expectedContentRegex = "1\\.2\\.3~[0-9a-f]+",
      beforeGradle = {
        initGitRepo()
      },
    )
  }

  @Test
  fun `version and gitHash properties can be overridden`() {
    assertVersionFile(
      expectedContentRegex = "9.9.9~deadbeef",
      beforeGradle = {
        writeRootProject(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile") {
            version.set("9.9.9")
            gitHash.set("deadbeef")
          }
          """,
          append = true,
        )
      },
    )
  }

  @Test
  fun `task overwrites existing version file`() {
    assertVersionFile(
      expectedContentRegex = "1.2.3~abc12345",
      beforeGradle = {
        writeRootProject(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile") {
            gitHash.set("abc12345")
          }
          """,
          append = true,
        )
        generatedVersionFile.run {
          parentFile.mkdirs()
          writeText("stale-version")
        }
      },
    )
  }

  @Test
  fun `version file generation is wired into main resources`() {
    assertVersionFile(
      expectedContentRegex = "1.2.3~abc12345",
      task = "processResources",
      beforeGradle = {
        writeRootProject(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile") {
            gitHash.set("abc12345")
          }
          """,
          append = true,
        )
      },
    )

    assertThat(builtResourceVersionFile).exists()
  }

  @Test
  fun `task is up-to-date on second run`() {
    assertVersionFile(
      expectedContentRegex = "1.2.3~abc12345",
      beforeGradle = {
        writeRootProject(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile") {
            gitHash.set("abc12345")
          }
          """,
          append = true,
        )
      },
    )

    val result = run("writeVersionNumberFile")

    assertThat(result.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `clean deletes version file`() {
    assertVersionFile(
      expectedContentRegex = "1.2.3~abc12345",
      beforeGradle = {
        writeRootProject(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile") {
            gitHash.set("abc12345")
          }
          """,
          append = true,
        )
      },
    )
    val versionFile = generatedVersionFile

    val result = run("clean")

    assertThat(result.task(":clean")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(versionFile).doesNotExist()
  }

  private fun assertVersionFile(
    expectedContentRegex: String,
    task: String = ":writeVersionNumberFile",
    beforeGradle: VersionPluginsFixture.() -> Unit = {},
  ): BuildResult {
    writeSettings(
      """
      rootProject.name = "my-lib"
      """
    )
    writeRootProject(
      """
      plugins {
        id("dd-trace-java.version-file")
      }

      version = "1.2.3"
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
