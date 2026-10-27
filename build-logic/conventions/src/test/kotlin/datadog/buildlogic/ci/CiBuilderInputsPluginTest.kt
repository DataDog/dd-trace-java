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
  fun `local builds add no builder inputs even when an image is supplied`() {
    fixture()
    directory.resolve("build.gradle").toFile().appendText(
      """

      tasks.register('inspectInputs') {
        doLast {
          assert !tasks.test.inputs.properties.containsKey('ciBuilderIdentity')
          assert !tasks.producer.inputs.properties.containsKey('ciBuilderIdentity')
        }
      }
      """.trimIndent(),
    )

    val local = environment(image = "not-an-immutable-image") - "GITLAB_CI"
    assertEquals(
      SUCCESS,
      runner("inspectInputs", configurationCache = false)
        .withEnvironment(local)
        .build()
        .task(":inspectInputs")
        ?.outcome,
    )
    assertEquals(
      SUCCESS,
      runner(
        "inspectInputs",
        configurationCache = false,
      ).withEnvironment(local + ("GITLAB_CI" to "false")).build().task(":inspectInputs")?.outcome,
    )
  }

  @Test
  fun `test cache follows the image digest without invalidating compilation`() {
    fixture()

    val first = runner("test").build()
    assertEquals(SUCCESS, first.task(":compileTestJava")?.outcome)
    assertEquals(SUCCESS, first.task(":test")?.outcome)

    val unchanged = runner("test").build()
    assertEquals(UP_TO_DATE, unchanged.task(":test")?.outcome)
    assertTrue(unchanged.output.contains("Reusing configuration cache"), unchanged.output)
    assertEquals(FROM_CACHE, runner("clean", "test").build().task(":test")?.outcome)

    val changed = runner("clean", "test").withEnvironment(environment(image('b'))).build()
    assertEquals(FROM_CACHE, changed.task(":compileTestJava")?.outcome)
    assertEquals(SUCCESS, changed.task(":test")?.outcome)

    val sameContents =
      runner("clean", "test")
        .withEnvironment(environment("other.example/builder@sha256:${"b".repeat(64)}"))
        .build()
    assertEquals(FROM_CACHE, sameContents.task(":test")?.outcome)
  }

  @Test
  fun `explicit producers follow the same builder identity`() {
    fixture()

    assertEquals(SUCCESS, runner("producer").build().task(":producer")?.outcome)
    assertEquals(FROM_CACHE, runner("clean", "producer").build().task(":producer")?.outcome)
    val changed = runner("producer").withEnvironment(environment(image('b'))).build()
    assertEquals(SUCCESS, changed.task(":producer")?.outcome)
  }

  @Test
  fun `operating system and architecture separate multi-platform image results`() {
    fixture()
    directory.resolve("build.gradle").toFile().appendText(
      """

      def platformField = providers.gradleProperty('changedPlatformField').orNull
      if (platformField != null) {
        def original = System.getProperty(platformField)
        System.setProperty(platformField, original + '-changed')
        gradle.buildFinished { System.setProperty(platformField, original) }
      }
      """.trimIndent(),
    )

    assertEquals(SUCCESS, runner("producer", configurationCache = false).build().task(":producer")?.outcome)
    for (field in listOf("os.name", "os.arch")) {
      val changed = runner("producer", "-PchangedPlatformField=$field", configurationCache = false).build()
      assertEquals(SUCCESS, changed.task(":producer")?.outcome, changed.output)
    }
  }

  @Test
  fun `invalid identity fails only when tracked task inputs are queried`() {
    fixture()
    val missing = environment() - "CI_BUILDER_IMAGE"
    assertEquals(
      SUCCESS,
      runner("help")
        .withEnvironment(missing)
        .build()
        .task(":help")
        ?.outcome,
    )

    val invalid =
      listOf(
        missing,
        environment(""),
        environment("registry.example/builder:ci-base"),
        environment("registry.example/builder@sha256:1234"),
        environment("registry.example/builder@sha256:${"z".repeat(64)}"),
        environment("registry.example/builder @sha256:${"a".repeat(64)}"),
      )
    for (values in invalid) {
      val failed = runner("producer").withEnvironment(values).buildAndFail()
      assertTrue(failed.output.contains("CI_BUILDER_IMAGE must be an immutable image reference"), failed.output)
    }
  }

  private fun fixture() {
    directory.resolve("settings.gradle").toFile().writeText(
      """
      rootProject.name = 'ci-builder-inputs-fixture'
      buildCache { local { directory = file('task-cache') } }
      """.trimIndent(),
    )
    directory.resolve("build.gradle").toFile().writeText(
      """
      plugins {
        id 'dd-trace-java.ci-builder-inputs'
        id 'java'
      }

      dependencies { testImplementation files(${junitClasspath()}) }
      test { useJUnitPlatform() }

      @CacheableTask
      abstract class Producer extends DefaultTask {
        @OutputFile
        abstract RegularFileProperty getOutputFile()

        @TaskAction
        void produce() {
          outputFile.get().asFile.text = 'built'
        }
      }

      def producer = tasks.register('producer', Producer) {
        group = 'verification'
        description = 'Produces an artifact using the builder environment.'
        outputFile = layout.buildDirectory.file('producer.txt')
      }
      ciBuilderInputs.track(producer)
      """.trimIndent(),
    )
    directory.resolve("src/test/java").toFile().mkdirs()
    directory.resolve("src/test/java/ExampleTest.java").toFile().writeText(
      """
      public class ExampleTest {
        @org.junit.jupiter.api.Test public void passes() {}
      }
      """.trimIndent(),
    )
  }

  private fun junitClasspath() =
    listOf(
      "org.junit.jupiter.api.Test",
      "org.junit.jupiter.engine.JupiterTestEngine",
      "org.junit.platform.engine.TestEngine",
      "org.junit.platform.launcher.Launcher",
      "org.junit.platform.commons.JUnitException",
      "org.opentest4j.AssertionFailedError",
    ).joinToString(", ") {
      "\"${Path.of(
        Class
          .forName(it)
          .protectionDomain.codeSource.location
          .toURI(),
      )}\""
    }

  private fun image(character: Char = 'a') = "registry.example/builder@sha256:${character.toString().repeat(64)}"

  private fun environment(image: String = image()) =
    (System.getenv() - "GITLAB_CI" - "CI_BUILDER_IMAGE") + mapOf("GITLAB_CI" to "true", "CI_BUILDER_IMAGE" to image)

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
