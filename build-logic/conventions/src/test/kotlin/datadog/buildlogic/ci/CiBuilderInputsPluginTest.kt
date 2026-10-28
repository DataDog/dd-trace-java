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
    processFixture()
    directory.resolve("build.gradle").toFile().appendText(
      """

      tasks.register('inspectInputs') {
        doLast {
          assert !tasks.test.inputs.properties.containsKey('ciBuilderIdentity')
          assert !tasks.execProcess.inputs.properties.containsKey('ciBuilderIdentity')
          assert !tasks.javaProcess.inputs.properties.containsKey('ciBuilderIdentity')
          assert ciBuilderInputs.identity.get().isEmpty()
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

    assertEquals(
      SUCCESS,
      runner("producer")
        .withEnvironment(local)
        .build()
        .task(":producer")
        ?.outcome,
    )
    assertEquals(
      FROM_CACHE,
      runner("clean", "producer")
        .withEnvironment(local + ("ORG_GRADLE_PROJECT_ciBuilderImage" to "registry.example/builder:next-"))
        .build()
        .task(":producer")
        ?.outcome,
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
  fun `consumer providers follow the same builder identity`() {
    fixture()

    assertEquals(SUCCESS, runner("producer").build().task(":producer")?.outcome)
    val unchanged = runner("producer").build()
    assertEquals(UP_TO_DATE, unchanged.task(":producer")?.outcome)
    assertTrue(unchanged.output.contains("Reusing configuration cache"), unchanged.output)
    assertEquals(FROM_CACHE, runner("clean", "producer").build().task(":producer")?.outcome)
    val changed = runner("producer").withEnvironment(environment("registry.example/builder:next-")).build()
    assertEquals(SUCCESS, changed.task(":producer")?.outcome)
    assertEquals("built", directory.resolve("build/producer.txt").toFile().readText())
  }

  @Test
  fun `process outputs follow CI identity and CI local transitions`() {
    processFixture()
    val tasks = arrayOf("execProcess", "javaProcess")

    val first = runner(*tasks).build()
    for (task in tasks) {
      assertEquals(SUCCESS, first.task(":$task")?.outcome, first.output)
      assertEquals("built", directory.resolve("build/$task.txt").toFile().readText())
    }

    val unchanged = runner(*tasks).build()
    assertTrue(unchanged.output.contains("Reusing configuration cache"), unchanged.output)
    for (task in tasks) {
      assertEquals(UP_TO_DATE, unchanged.task(":$task")?.outcome, unchanged.output)
    }

    val changed = runner(*tasks).withEnvironment(environment("registry.example/builder:next-")).build()
    for (task in tasks) {
      assertEquals(SUCCESS, changed.task(":$task")?.outcome, changed.output)
    }

    val local = environment("") - "GITLAB_CI"
    val localBuild = runner(*tasks).withEnvironment(local).build()
    for (task in tasks) {
      assertEquals(SUCCESS, localBuild.task(":$task")?.outcome, localBuild.output)
    }

    val unchangedLocal = runner(*tasks).withEnvironment(local).build()
    assertTrue(unchangedLocal.output.contains("Reusing configuration cache"), unchangedLocal.output)
    for (task in tasks) {
      assertEquals(UP_TO_DATE, unchangedLocal.task(":$task")?.outcome, unchangedLocal.output)
    }

    val backInCi = runner(*tasks).build()
    for (task in tasks) {
      assertEquals(SUCCESS, backInCi.task(":$task")?.outcome, backInCi.output)
    }
  }

  @Test
  fun `Kotlin ad hoc inputs accept empty local identity and track CI identity`() {
    fixture()
    directory.resolve("build.gradle").toFile().delete()
    directory.resolve("build.gradle.kts").toFile().writeText(
      """
      plugins {
        id("dd-trace-java.ci-builder-inputs")
      }

      tasks.register("producer") {
        inputs.property("ciBuilderIdentity", ciBuilderInputs.identity)
        val artifact = layout.buildDirectory.file("producer.txt")
        outputs.file(artifact)
        doLast { artifact.get().asFile.writeText("built") }
      }
      """.trimIndent(),
    )
    val local = environment("") - "GITLAB_CI"

    assertEquals(
      SUCCESS,
      runner("producer")
        .withEnvironment(local)
        .build()
        .task(":producer")
        ?.outcome,
    )
    val unchanged = runner("producer").withEnvironment(local).build()
    assertEquals(UP_TO_DATE, unchanged.task(":producer")?.outcome, unchanged.output)
    assertTrue(unchanged.output.contains("Reusing configuration cache"), unchanged.output)
    assertEquals(SUCCESS, runner("producer").build().task(":producer")?.outcome)
    val changed = runner("producer").withEnvironment(environment("registry.example/builder:next-")).build()
    assertEquals(SUCCESS, changed.task(":producer")?.outcome, changed.output)
    assertEquals("built", directory.resolve("build/producer.txt").toFile().readText())
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
    processFixture()
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
      for (task in listOf("producer", "execProcess", "javaProcess")) {
        val failed = runner(task).withEnvironment(values).buildAndFail()
        assertTrue(failed.output.contains("ciBuilderImage must be a nonblank CI builder identity"), failed.output)
      }
    }
  }

  private fun processFixture() {
    fixture()
    directory.resolve("src/main/java").toFile().mkdirs()
    directory.resolve("src/main/java/Writer.java").toFile().writeText(
      """
      import java.nio.charset.StandardCharsets;
      import java.nio.file.Files;
      import java.nio.file.Path;
      import java.nio.file.Paths;

      public class Writer {
        public static void main(String[] args) throws Exception {
          Path output = Paths.get(args[0]);
          Files.createDirectories(output.getParent());
          Files.write(output, "built".getBytes(StandardCharsets.UTF_8));
        }
      }
      """.trimIndent(),
    )
    directory.resolve("build.gradle").toFile().appendText(
      """

      tasks.register('execProcess', Exec) {
        inputs.files(sourceSets.main.output)
        def artifact = layout.buildDirectory.file('execProcess.txt')
        outputs.file(artifact)
        commandLine new File(System.getProperty('java.home'), 'bin/java'),
          '-cp', sourceSets.main.output.classesDirs.asPath, 'Writer', artifact.get().asFile.absolutePath
      }

      tasks.register('javaProcess', JavaExec) {
        classpath = sourceSets.main.runtimeClasspath
        mainClass = 'Writer'
        def artifact = layout.buildDirectory.file('javaProcess.txt')
        outputs.file(artifact)
        args artifact.get().asFile.absolutePath
      }
      """.trimIndent(),
    )
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
      producer.configure {
        inputs.property('ciBuilderIdentity', ciBuilderInputs.identity)
      }
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
