package datadog.buildlogic.ci

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CiBuilderInputsPluginTest {
  @TempDir
  lateinit var directory: Path

  @Test
  fun `builder identity invalidates cached outputs and reuses configuration`() {
    fixture()
    assertEquals(SUCCESS, runner("produce").build().task(":produce")?.outcome)

    val unchanged = runner("produce").build()
    assertEquals(UP_TO_DATE, unchanged.task(":produce")?.outcome)
    assertTrue(unchanged.output.contains("Reusing configuration cache"), unchanged.output)
    assertEquals(FROM_CACHE, runner("clean", "produce").build().task(":produce")?.outcome)

    val changed = runner("produce").withEnvironment(environment("builder:next")).build()
    assertEquals(SUCCESS, changed.task(":produce")?.outcome)
    assertEquals("built", directory.resolve("build/artifact.txt").toFile().readText())
  }

  @Test
  fun `automatic inputs apply in CI and local identity is empty`() {
    fixture()
    assertEquals(SUCCESS, runner("inspect", configurationCache = false).build().task(":inspect")?.outcome)
    val local = environment("") - "GITLAB_CI"
    assertEquals(
      SUCCESS,
      runner("inspect", "produce", configurationCache = false)
        .withEnvironment(local)
        .build()
        .task(":inspect")
        ?.outcome,
    )
  }

  @Test
  fun `missing and blank identities fail only when inputs are queried`() {
    fixture()
    val missing = environment() - "ORG_GRADLE_PROJECT_ciBuilderImage"
    assertEquals(
      SUCCESS,
      runner("help")
        .withEnvironment(missing)
        .build()
        .task(":help")
        ?.outcome,
    )
    for (values in listOf(missing, environment(" "))) {
      val failed = runner("produce").withEnvironment(values).buildAndFail()
      assertTrue(failed.output.contains("ciBuilderImage must be a nonblank CI builder identity"), failed.output)
    }
  }

  private fun fixture() {
    directory.resolve("settings.gradle.kts").toFile().writeText(
      """
      rootProject.name = "ci-builder-inputs-fixture"
      buildCache { local { directory = file("task-cache") } }
      """.trimIndent(),
    )
    directory.resolve("build.gradle.kts").toFile().writeText(
      """
      import org.gradle.api.tasks.testing.Test

      plugins {
        base
        id("dd-trace-java.ci-builder-inputs")
      }

      val identity = ciBuilderInputs.identity
      val ci = providers.environmentVariable("GITLAB_CI").orNull == "true"
      val automaticTasks = listOf(
        tasks.register<Test>("test"),
        tasks.register<Exec>("exec"),
        tasks.register<JavaExec>("javaExec")
      )
      tasks.register("inspect") {
        doLast {
          check(identity.get().isEmpty() == !ci)
          val expectedIdentity = if (ci) identity.get() else null
          for (task in automaticTasks) {
            check(task.get().inputs.properties["ciBuilderIdentity"] == expectedIdentity)
          }
        }
      }

      @CacheableTask
      abstract class Produce : DefaultTask() {
        @get:OutputFile abstract val artifact: RegularFileProperty

        @TaskAction
        fun produce() { artifact.get().asFile.writeText("built") }
      }
      tasks.register<Produce>("produce") {
        inputs.property("ciBuilderIdentity", identity)
        artifact.set(layout.buildDirectory.file("artifact.txt"))
      }
      """.trimIndent(),
    )
  }

  private fun environment(image: String = "builder:current") =
    (System.getenv() - "GITLAB_CI" - "ORG_GRADLE_PROJECT_ciBuilderImage") +
      mapOf("GITLAB_CI" to "true", "ORG_GRADLE_PROJECT_ciBuilderImage" to image)

  private fun runner(
    vararg arguments: String,
    configurationCache: Boolean = true,
  ): GradleRunner =
    GradleRunner
      .create()
      .withProjectDir(directory.toFile())
      .withPluginClasspath()
      .withEnvironment(environment())
      .withArguments(
        *arguments,
        "--build-cache",
        if (configurationCache) "--configuration-cache" else "--no-configuration-cache",
        "--stacktrace",
        "--max-workers=2",
        "-Dorg.gradle.jvmargs=-Xmx512m",
      )
}
