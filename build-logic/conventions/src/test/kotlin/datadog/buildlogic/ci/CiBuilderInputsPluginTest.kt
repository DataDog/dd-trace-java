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
    smokeFixture(smokePluginFirst = true)
    directory.resolve("build.gradle").toFile().appendText(
      """

      tasks.register('inspectInputs') {
        doLast {
          assert !tasks.test.inputs.properties.containsKey('ciBuilderIdentity')
          assert !tasks.producer.inputs.properties.containsKey('ciBuilderIdentity')
          assert !tasks.gradleProducer.inputs.properties.containsKey('ciBuilderIdentity')
          assert !tasks.mavenProducer.inputs.properties.containsKey('ciBuilderIdentity')
        }
      }
      """.trimIndent(),
    )

    val local = environment(image = "") - "GITLAB_CI"
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
  fun `test cache follows tag identities without invalidating compilation`() {
    fixture()

    val first = runner("test").build()
    assertEquals(SUCCESS, first.task(":compileTestJava")?.outcome)
    assertEquals(SUCCESS, first.task(":test")?.outcome)

    val unchanged = runner("test").build()
    assertEquals(UP_TO_DATE, unchanged.task(":test")?.outcome)
    assertTrue(unchanged.output.contains("Reusing configuration cache"), unchanged.output)
    assertEquals(FROM_CACHE, runner("clean", "test").build().task(":test")?.outcome)

    val changed = runner("clean", "test").withEnvironment(environment("registry.example/builder:next-")).build()
    assertEquals(FROM_CACHE, changed.task(":compileTestJava")?.outcome)
    assertEquals(SUCCESS, changed.task(":test")?.outcome)

    val hashProperty = "-PciBuilderImage=sha256:${"b".repeat(64)}"
    val futureHash =
      runner("clean", "test", hashProperty)
        .withEnvironment(environment(""))
        .build()
    assertEquals(FROM_CACHE, futureHash.task(":compileTestJava")?.outcome)
    assertEquals(SUCCESS, futureHash.task(":test")?.outcome)

    val overriddenEnvironment =
      runner("clean", "test", hashProperty)
        .withEnvironment(environment("registry.example/builder:another-tag"))
        .build()
    assertEquals(FROM_CACHE, overriddenEnvironment.task(":test")?.outcome)
  }

  @Test
  fun `explicit producers follow the same builder identity`() {
    fixture()

    assertEquals(SUCCESS, runner("producer").build().task(":producer")?.outcome)
    assertEquals(FROM_CACHE, runner("clean", "producer").build().task(":producer")?.outcome)
    val changed = runner("producer").withEnvironment(environment("registry.example/builder:next-")).build()
    assertEquals(SUCCESS, changed.task(":producer")?.outcome)
  }

  @Test
  fun `smoke producers track builder identity when the smoke plugin is applied first`() {
    smokeProducerCache(smokePluginFirst = true)
  }

  @Test
  fun `smoke producers track builder identity when the convention plugin is applied first`() {
    smokeProducerCache(smokePluginFirst = false)
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
    val missing = environment() - "ORG_GRADLE_PROJECT_ciBuilderImage"
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
        environment(" \t "),
      )
    for (values in invalid) {
      val failed = runner("producer").withEnvironment(values).buildAndFail()
      assertTrue(failed.output.contains("ciBuilderImage must be a nonblank CI builder identity"), failed.output)
    }
  }

  private fun smokeProducerCache(smokePluginFirst: Boolean) {
    smokeFixture(smokePluginFirst)
    val taskNames = arrayOf("gradleProducer", "mavenProducer", "compileTestJava")

    val first = runner(*taskNames).build()
    for (taskName in taskNames) {
      assertEquals(SUCCESS, first.task(":$taskName")?.outcome, first.output)
    }

    val unchanged = runner(*taskNames).build()
    assertTrue(unchanged.output.contains("Reusing configuration cache"), unchanged.output)
    for (taskName in taskNames) {
      assertEquals(UP_TO_DATE, unchanged.task(":$taskName")?.outcome, unchanged.output)
    }

    val restored = runner("clean", *taskNames).build()
    for (taskName in taskNames) {
      assertEquals(FROM_CACHE, restored.task(":$taskName")?.outcome, restored.output)
    }

    val changed = runner("clean", *taskNames).withEnvironment(environment("registry.example/builder:next-")).build()
    assertEquals(FROM_CACHE, changed.task(":compileTestJava")?.outcome, changed.output)
    for (taskName in listOf("gradleProducer", "mavenProducer")) {
      assertEquals(SUCCESS, changed.task(":$taskName")?.outcome, changed.output)
      assertEquals("built", directory.resolve("build/$taskName/artifact.txt").toFile().readText())
    }
  }

  private fun smokeFixture(smokePluginFirst: Boolean) {
    val smokePlugin = "dd-trace-java.smoke-test-app"
    val conventionPlugin = "dd-trace-java.ci-builder-inputs"
    val pluginOrder =
      if (smokePluginFirst) listOf(smokePlugin, conventionPlugin) else listOf(conventionPlugin, smokePlugin)
    fixture(listOf("java") + pluginOrder)
    directory.resolve("application").toFile().mkdirs()
    directory.resolve("application/source.txt").toFile().writeText("source")
    directory.resolve("mvnw").toFile().writeText("fixture Maven wrapper")
    directory.resolve("build.gradle").toFile().appendText(
      """

      smokeTestApp {
        javaLauncher = javaToolchains.launcherFor {
          languageVersion = JavaLanguageVersion.of(JavaVersion.current().majorVersion)
        }
        gradleApp {
          taskName = 'gradleProducer'
          artifactPath = 'artifact.txt'
          sysProperty = 'gradle.artifact'
        }
        mavenApp {
          taskName = 'mavenProducer'
          artifactPath = 'artifact.txt'
          sysProperty = 'maven.artifact'
        }
      }

      // Exercise the real producer types and their inputs without starting nested tools.
      ['gradleProducer', 'mavenProducer'].each { producerName ->
        tasks.named(producerName) {
          applicationBuildDir = layout.buildDirectory.dir(producerName)
          actions.clear()
          doLast { task ->
            def artifact = task.applicationBuildDir.file('artifact.txt').get().asFile
            artifact.parentFile.mkdirs()
            artifact.text = 'built'
          }
        }
      }
      """.trimIndent(),
    )
  }

  private fun fixture(plugins: List<String> = listOf("dd-trace-java.ci-builder-inputs", "java")) {
    directory.resolve("settings.gradle").toFile().writeText(
      """
      rootProject.name = 'ci-builder-inputs-fixture'
      buildCache { local { directory = file('task-cache') } }
      """.trimIndent(),
    )
    directory.resolve("build.gradle").toFile().writeText(
      """
      plugins {
        ${plugins.joinToString("\n") { "id '$it'" }}
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

  private fun environment(image: String = "registry.example/builder:ci-") =
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
