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

  @Test
  fun `generated version file is ignored in runtime classpath normalization`() {
    writeSettings(
      """
      rootProject.name = "my-lib"
      """
    )
    writeRootProject(
      """
      import org.gradle.api.DefaultTask
      import org.gradle.api.file.ConfigurableFileCollection
      import org.gradle.api.file.RegularFileProperty
      import org.gradle.api.tasks.Classpath
      import org.gradle.api.tasks.InputFiles
      import org.gradle.api.tasks.OutputFile
      import org.gradle.api.tasks.TaskAction

      plugins {
        id("dd-trace-java.version-file")
      }

      version = "1.2.3"

      tasks.named<datadog.gradle.plugin.version.WriteVersionFile>("writeVersionNumberFile") {
        gitHash.set(providers.gradleProperty("gitHash").orElse("abc12345"))
      }

      abstract class ClasspathProbe : DefaultTask() {
        @get:InputFiles
        @get:Classpath
        val classpath: ConfigurableFileCollection = project.objects.fileCollection()

        @get:OutputFile
        val outputFile: RegularFileProperty = project.objects.fileProperty()

        @TaskAction
        fun probe() {
          outputFile.get().asFile.writeText("probed")
        }
      }

      tasks.register<ClasspathProbe>("classpathProbe") {
        dependsOn("processResources")
        classpath.from(sourceSets.main.get().runtimeClasspath)
        outputFile.set(layout.buildDirectory.file("classpath-probe/output.txt"))
      }
      """
    )

    assertThat(run("classpathProbe", "-PgitHash=abc12345").task(":classpathProbe")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)

    val result = run("classpathProbe", "-PgitHash=def67890")

    assertThat(generatedVersionFile).hasContent("1.2.3~def67890")
    assertThat(result.task(":writeVersionNumberFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":classpathProbe")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
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
