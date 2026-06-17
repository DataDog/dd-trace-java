package datadog.gradle.plugin.version

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

class WriteVersionFilePluginTest : VersionPluginsFixture() {

  @Test
  fun `writes stable local development version when CI is not true`() {
    assertVersionFile(
      expectedContentRegex = "1\\.2\\.3-SNAPSHOT\\.dev",
    )
  }

  @Test
  fun `adds dev identifier to existing local snapshot version`() {
    assertVersionFile(
      expectedContentRegex = "1\\.2\\.3-SNAPSHOT\\.dev",
      beforeGradle = {
        writeRootProject(
          """

          tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile") {
            version.set("1.2.3-SNAPSHOT")
          }
          """,
          append = true,
        )
      },
    )
  }

  @Test
  fun `writes version file in version~hash format when CI is true`() {
    assertVersionFile(
      expectedContentRegex = "1\\.2\\.3~[0-9a-f]+",
      env = mapOf("CI" to "true"),
      beforeGradle = {
        initGitRepo()
      },
    )
  }

  @Test
  fun `tagged release version only gets local snapshot suffix when CI is not true`() {
    writeSettings(
      """
      rootProject.name = "my-lib"
      """
    )
    writeRootProject(
      """
      plugins {
        id("dd-trace-java.tracer-version")
        id("dd-trace-java.version-file")
      }
      """
    )
    initGitRepo()
    exec("git", "tag", "v1.2.3", "-m", "")

    val ciResult = run("writeVersionNumberFile", env = mapOf("CI" to "true"))

    assertThat(ciResult.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(generatedVersionFile.readText()).matches("1\\.2\\.3~[0-9a-f]+")

    val localResult = run("writeVersionNumberFile", env = mapOf("CI" to "false"))

    assertThat(localResult.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(generatedVersionFile).hasContent("1.2.3-SNAPSHOT.dev")
  }

  @Test
  fun `version and gitHash properties can be overridden in CI`() {
    assertVersionFile(
      expectedContentRegex = "9\\.9\\.9~deadbeef",
      env = mapOf("CI" to "true"),
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
      expectedContentRegex = "1\\.2\\.3-SNAPSHOT\\.dev",
      beforeGradle = {
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
      expectedContentRegex = "1\\.2\\.3-SNAPSHOT\\.dev",
      task = "processResources",
    )

    assertThat(builtResourceVersionFile).exists()
  }

  @Test
  fun `task is up-to-date on second run when CI is not true`() {
    assertVersionFile(
      expectedContentRegex = "1\\.2\\.3-SNAPSHOT\\.dev",
    )

    val result = run("writeVersionNumberFile", env = mapOf("CI" to "false"))

    assertThat(result.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `task is up-to-date after empty commit when CI is not true`() {
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
    initGitRepo()

    val firstResult = run("writeVersionNumberFile", env = mapOf("CI" to "false"))
    val firstContent = generatedVersionFile.readText()

    exec("git", "commit", "--allow-empty", "-m", "Empty commit")
    val secondResult = run("writeVersionNumberFile", env = mapOf("CI" to "false"))

    assertThat(firstResult.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(secondResult.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(generatedVersionFile).hasContent(firstContent)
  }

  @Test
  fun `clean deletes version file`() {
    assertVersionFile(
      expectedContentRegex = "1\\.2\\.3-SNAPSHOT\\.dev",
    )
    val versionFile = generatedVersionFile

    val result = run("clean", env = mapOf("CI" to "false"))

    assertThat(result.task(":clean")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(versionFile).doesNotExist()
  }

  private fun assertVersionFile(
    expectedContentRegex: String,
    task: String = ":writeVersionNumberFile",
    env: Map<String, String> = mapOf("CI" to "false"),
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

    val buildResult = run(task, env = env)
    val taskPath = if (task.startsWith(":")) task else ":$task"

    assertThat(buildResult.task(taskPath)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(generatedVersionFile).exists().isFile()
    assertThat(generatedVersionFile.readText()).matches(expectedContentRegex)
    return buildResult
  }
}
