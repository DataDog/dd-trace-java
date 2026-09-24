package datadog.buildlogic.testcontainers

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TestcontainersPluginTest {
  @TempDir
  lateinit var directory: Path

  @RegisterExtension
  @JvmField
  val registry = RegistryExtension()

  @Test
  fun `moving images are refreshed before cache lookup including configuration cache reuse`() {
    fixture(registry.image)

    assertThat(run("help").task(":help")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(registry.requestCount).isZero()

    assertThat(run("test", "-PskipTests").task(":test")?.outcome).isEqualTo(TaskOutcome.SKIPPED)
    assertThat(registry.requestCount).isZero()

    assertThat(run("test").task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(testReport()).content().contains("library/cassandra@${registry.digest}")

    val firstRequests = registry.requestCount
    val warm = run("test")

    assertThat(warm.output).contains("Reusing configuration cache")
    assertThat(warm.task(":test")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(registry.requestCount).isGreaterThan(firstRequests)

    registry.imageVersion = 2
    val changed = run("test")

    assertThat(changed.output).contains("Reusing configuration cache")
    assertThat(changed.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(testReport()).content().contains("library/cassandra@${registry.digest}")

    run("clean")
    val restored = run("test")

    assertThat(restored.output).contains("Reusing configuration cache")
    assertThat(restored.task(":test")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    assertThat(testReport()).content().contains("library/cassandra@${registry.digest}")

    val suites = run("forkedTest", "latestDepTest", "latestDepTestForkedTest", "latestDepForkedTest", "replayedTests")

    assertThat(suites.task(":replayedTests")?.outcome)
      .isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    assertThat(testReport("forkedTest", "ImageForkedTest"))
      .content()
      .contains("library/cassandra@${registry.digest}")
    assertThat(testReport("forkedTest", "ImageTest")).doesNotExist()
    assertThat(testReport(testClass = "ImageForkedTest")).doesNotExist()
    assertThat(testReport("latestDepTest"))
      .content()
      .contains("library/cassandra@${registry.digest}")
    assertThat(testReport("latestDepTestForkedTest", "ImageForkedTest"))
      .content()
      .contains("library/cassandra@${registry.digest}")
    assertThat(testReport("replayedTests"))
      .content()
      .contains("library/cassandra@${registry.digest}")
    assertThat(testReport("latestDepForkedTest", "ImageForkedTest"))
      .content()
      .contains("library/cassandra@${registry.digest}")

    val reusedSuites = run("forkedTest", "latestDepTest", "latestDepTestForkedTest", "latestDepForkedTest", "replayedTests")

    assertThat(reusedSuites.output).contains("Reusing configuration cache")
    assertThat(reusedSuites.task(":forkedTest")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(reusedSuites.task(":latestDepTestForkedTest")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(reusedSuites.task(":replayedTests")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

    assertThat(run("declaredTest").task(":declaredTest")?.outcome).isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    assertThat(testReport("declaredTest"))
      .content()
      .contains("library/cassandra@${registry.digest}")

    val requestsBeforeUnrelatedSuite = registry.requestCount
    assertThat(run("isolatedTest").task(":isolatedTest")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(run("testForkedTest").task(":testForkedTest")?.outcome).isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    assertThat(run("emptyTest").task(":emptyTest")?.outcome).isEqualTo(TaskOutcome.NO_SOURCE)
    assertThat(registry.requestCount).isEqualTo(requestsBeforeUnrelatedSuite)

    registry.unavailable = true
    assertThat(runner("test").buildAndFail().output)
      .contains("Cannot resolve test container image")
  }

  @Test
  fun `image configurations support shared parents and tests without matching source sets`() {
    val image = "cassandra@sha256:${"a".repeat(64)}"
    val extraImage = "redis@sha256:${"b".repeat(64)}"
    fixture(image)
    directory.resolve("build.gradle.kts").toFile().appendText(
      """

      val databaseImages = configurations.dependencyScope("databaseImages")
      val sharedImplementation = configurations.dependencyScope("sharedImplementation")

      configurations.named("sharedContainerImage") {
        extendsFrom(databaseImages.get())
      }
      configurations.testContainerImage { dependencies.clear() }
      configurations.testImplementation { extendsFrom(sharedImplementation.get()) }
      configurations.dependencyScope("databaseCheckContainerImage") {
        extendsFrom(databaseImages.get())
      }

      dependencies {
        add(databaseImages.name, image("$image", "test.cassandra.image"))
        add("databaseCheckContainerImage", image("$extraImage", "test.redis.image"))
      }

      tasks.register<Test>("databaseCheck") {
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
      }
      """.trimIndent(),
    )

    val first = run("test", "databaseCheck")

    assertThat(first.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(first.task(":databaseCheck")?.outcome).isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    assertThat(testReport()).content().contains("registry-1.docker.io/library/$image")
    assertThat(testReport("databaseCheck"))
      .content()
      .contains("registry-1.docker.io/library/$image")
      .contains("registry-1.docker.io/library/$extraImage")

    val reused = run("test", "databaseCheck")

    assertThat(reused.output).contains("Reusing configuration cache")
    assertThat(reused.task(":test")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(reused.task(":databaseCheck")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(registry.requestCount).isZero()

    run("databaseCheck")
    Files.createDirectories(directory.resolve("src/test/resources"))
    directory
      .resolve("src/test/resources/testcontainers.properties")
      .toFile()
      .writeText("image.substitutor=untracked.CustomSubstitutor\n")

    val failed = runner("databaseCheck").buildAndFail()

    assertThat(failed.output)
      .contains("Reusing configuration cache")
      .contains("org.gradle.api.InvalidUserDataException: Custom Testcontainers image substitutions")
      .contains("move image overrides into testContainerImage declarations")
  }

  @Test
  fun `tasks running multiple source sets reject conflicting inherited images`() {
    fixture("cassandra@sha256:${"a".repeat(64)}")
    directory.resolve("build.gradle.kts").toFile().appendText(
      """

      configurations.named("declaredTestContainerImage") { dependencies.clear() }
      dependencies {
        add("declaredTestContainerImage", image("cassandra@sha256:${"b".repeat(64)}", "test.cassandra.image"))
      }

      tasks.register<Test>("combinedTests") {
        testClassesDirs = files(sourceSets.test.get().output.classesDirs, sourceSets["declaredTest"].output.classesDirs)
        classpath = sourceSets.test.get().runtimeClasspath + sourceSets["declaredTest"].runtimeClasspath
      }
      """.trimIndent(),
    )

    assertThat(runner("combinedTests").buildAndFail().output)
      .contains("org.gradle.api.InvalidUserDataException: Conflicting container images for 'test.cassandra.image'")
    assertThat(registry.requestCount).isZero()
  }

  @Test
  fun `different image attributes for the same property are rejected`() {
    fixture("cassandra@sha256:${"a".repeat(64)}")
    directory.resolve("build.gradle.kts").toFile().appendText(
      """

      dependencies {
        testContainerImage(image("cassandra@sha256:${"b".repeat(64)}", "test.cassandra.image"))
      }
      """.trimIndent(),
    )

    assertThat(runner("test").buildAndFail().output)
      .contains("org.gradle.api.InvalidUserDataException: Conflicting container images for 'test.cassandra.image'")
    assertThat(registry.requestCount).isZero()
  }

  @Test
  fun `hub prefix is resolved and pinned references require no registry`() {
    val digest = "sha256:${"a".repeat(64)}"
    fixture("cassandra@$digest")

    val result =
      runner("test")
        .withEnvironment(
          System.getenv() +
            ("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX" to "mirror.example/team/"),
        ).build()

    assertThat(result.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(testReport()).content().contains("mirror.example/team/cassandra@$digest")

    val changed =
      runner("test")
        .withEnvironment(
          System.getenv() +
            ("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX" to "another.example/team/"),
        ).build()

    assertThat(changed.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(testReport()).content().contains("another.example/team/cassandra@$digest")

    directory.resolve("build.gradle.kts").toFile().appendText(
      """

      sourceSets.test { resources.setSrcDirs(listOf("lateResources")) }
      tasks.named<Test>("test") {
        environment("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX", "task.example/")
      }
      """.trimIndent(),
    )

    assertThat(run("test").task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(testReport()).content().contains("task.example/cassandra@$digest")

    Files.createDirectories(directory.resolve("lateResources"))
    directory
      .resolve("lateResources/testcontainers.properties")
      .toFile()
      .writeText("image.substitutor=untracked.CustomSubstitutor\n")

    assertThat(runner("test").buildAndFail().output)
      .contains("move image overrides into testContainerImage declarations")
  }

  @Test
  fun `task environment removals override inherited Testcontainers settings`() {
    val digest = "sha256:${"a".repeat(64)}"
    fixture("cassandra@$digest")
    directory.resolve("build.gradle.kts").toFile().appendText(
      """

      tasks.named<Test>("test") {
        environment.remove("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX")
        environment.remove("TESTCONTAINERS_IMAGE_SUBSTITUTOR")
      }
      """.trimIndent(),
    )
    val environment =
      System.getenv() +
        mapOf(
          "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX" to "removed.example/team/",
          "TESTCONTAINERS_IMAGE_SUBSTITUTOR" to "removed.CustomSubstitutor",
        )

    val first = runner("test").withEnvironment(environment).build()

    assertThat(first.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(testReport()).content().contains("registry-1.docker.io/library/cassandra@$digest")

    val reused = runner("test").withEnvironment(environment).build()

    assertThat(reused.output).contains("Reusing configuration cache")
    assertThat(reused.task(":test")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `one can write Groovy DSL dependency`() {
    val image = "cassandra@sha256:${"a".repeat(64)}"
    fixture(image)
    Files.delete(directory.resolve("build.gradle.kts"))
    directory.resolve("build.gradle").toFile().writeText(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.testcontainers'
      }

      sourceSets {
        integrationTest { java.srcDirs = sourceSets.test.java.srcDirs }
      }

      dependencies {
        testImplementation files(${junitClasspath()})
        integrationTestImplementation files(${junitClasspath()})
        testContainerImage(image('$image', 'test.cassandra.image'))
        integrationTestContainerImage(image('$image', 'test.cassandra.image'))
      }

      tasks.register('integrationTest', Test) {
        testClassesDirs = sourceSets.integrationTest.output.classesDirs
        classpath = sourceSets.integrationTest.runtimeClasspath
      }
      tasks.withType(Test).configureEach { useJUnitPlatform() }
      """.trimIndent(),
    )

    val result = run("test", "integrationTest")

    assertThat(result.task(":test")?.outcome).isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    assertThat(result.task(":integrationTest")?.outcome).isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    assertThat(testReport()).content().contains("registry-1.docker.io/library/$image")
    assertThat(testReport("integrationTest"))
      .content()
      .contains("registry-1.docker.io/library/$image")

    val reused = run("test", "integrationTest")

    assertThat(reused.output).contains("Reusing configuration cache")
    assertThat(reused.task(":test")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(reused.task(":integrationTest")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  private fun fixture(image: String) {
    directory.resolve("settings.gradle.kts").toFile().writeText(
      """
      rootProject.name = "container-image-fixture"
      buildCache { local { directory = file("cache") } }
      """.trimIndent(),
    )
    directory.resolve("build.gradle.kts").toFile().writeText(
      """
      import datadog.buildlogic.testcontainers.image

      plugins {
        java
        id("dd-trace-java.testcontainers")
      }

      val latestDepTest = sourceSets.create("latestDepTest") {
        java.setSrcDirs(sourceSets.test.get().java.srcDirs)
      }
      val latestDepForkedTest = sourceSets.create("latestDepForkedTest") {
        java.setSrcDirs(sourceSets.test.get().java.srcDirs)
      }
      val declaredTest = sourceSets.create("declaredTest") {
        java.setSrcDirs(sourceSets.test.get().java.srcDirs)
      }
      val isolatedTest = sourceSets.create("isolatedTest")
      val emptyTest = sourceSets.create("emptyTest")

      tasks.register<Test>("forkedTest") {
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
      }

      tasks.register<Test>("latestDepTest") {
        testClassesDirs = latestDepTest.output.classesDirs
        classpath = latestDepTest.runtimeClasspath
      }

      tasks.register<Test>("latestDepTestForkedTest") {
        testClassesDirs = latestDepTest.output.classesDirs
        classpath = latestDepTest.runtimeClasspath
      }

      tasks.register<Test>("replayedTests") {
        testClassesDirs = latestDepTest.output.classesDirs
        classpath = latestDepTest.runtimeClasspath
      }

      tasks.register<Test>("latestDepForkedTest") {
        testClassesDirs = latestDepForkedTest.output.classesDirs
        classpath = latestDepForkedTest.runtimeClasspath
      }

      tasks.register<Test>("declaredTest") {
        testClassesDirs = declaredTest.output.classesDirs
        classpath = declaredTest.runtimeClasspath
      }

      tasks.register<Test>("isolatedTest") {
        testClassesDirs = isolatedTest.output.classesDirs
        classpath = isolatedTest.runtimeClasspath
      }

      tasks.register<Test>("testForkedTest") {
        testClassesDirs = isolatedTest.output.classesDirs
        classpath = isolatedTest.runtimeClasspath
      }

      tasks.register<Test>("emptyTest") {
        testClassesDirs = emptyTest.output.classesDirs
        classpath = emptyTest.runtimeClasspath
      }

      tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        val skip = providers.gradleProperty("skipTests")
        onlyIf { !skip.isPresent }

        // Match dd-trace-java.configure-tests: split regular and forked test classes.
        if (name.startsWith("forkedTest") || name.endsWith("ForkedTest")) {
          setExcludes(emptyList())
          setIncludes(listOf("**/*ForkedTest*"))
          forkEvery = 1
        } else {
          exclude("**/*ForkedTest*")
          failOnNoDiscoveredTests = false
        }
      }

      // Plugins must see declarations and inheritance added after tasks are realized.
      tasks.named("test").get()
      tasks.named("latestDepTest").get()
      tasks.named("latestDepTestForkedTest").get()
      tasks.named("replayedTests").get()
      configurations.named(latestDepTest.implementationConfigurationName) {
        extendsFrom(configurations.testImplementation.get())
      }
      configurations.named(latestDepForkedTest.implementationConfigurationName) {
        extendsFrom(configurations.getByName(latestDepTest.implementationConfigurationName))
      }
      configurations.named(emptyTest.implementationConfigurationName) {
        extendsFrom(configurations.testImplementation.get())
      }

      dependencies {
        testImplementation(files(${junitClasspath()}))
        add(declaredTest.implementationConfigurationName, files(${junitClasspath()}))
        add(isolatedTest.implementationConfigurationName, files(${junitClasspath()}))
        testContainerImage(image("$image", "test.cassandra.image"))
        add("declaredTestContainerImage", image("$image", "test.cassandra.image"))
      }
      """.trimIndent(),
    )

    Files.createDirectories(directory.resolve("src/test/java"))
    directory.resolve("src/test/java/ImageTest.java").toFile().writeText(
      """
      import org.junit.jupiter.api.Test;
      import static org.junit.jupiter.api.Assertions.assertTrue;

      public class ImageTest {
        @Test public void imageIsPinned() {
          String image = System.getProperty("test.cassandra.image");
          assertTrue(image.matches(".+@sha256:[a-f0-9]{64}"), image);
          System.out.println(image);
          System.out.println(System.getProperty("test.redis.image", ""));
        }
      }
      """.trimIndent(),
    )
    directory.resolve("src/test/java/ImageForkedTest.java").toFile().writeText(
      "public class ImageForkedTest extends ImageTest {}\n",
    )

    Files.createDirectories(directory.resolve("src/isolatedTest/java"))
    directory.resolve("src/isolatedTest/java/PlainTest.java").toFile().writeText(
      """
      import org.junit.jupiter.api.Test;
      import static org.junit.jupiter.api.Assertions.assertNull;

      public class PlainTest {
        @Test public void hasNoContainerDependency() {
          assertNull(System.getProperty("test.cassandra.image"));
        }
      }
      """.trimIndent(),
    )
    directory.resolve("src/isolatedTest/java/PlainForkedTest.java").toFile().writeText(
      "public class PlainForkedTest extends PlainTest {}\n",
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

  private fun runner(vararg arguments: String) = GradleRunner
    .create()
    .withProjectDir(directory.toFile())
    .withPluginClasspath()
    .withArguments(
      *arguments,
      "--build-cache",
      "--configuration-cache",
      "--stacktrace",
      "--max-workers=2",
      "-Dorg.gradle.jvmargs=-Xmx512m",
    )

  private fun run(vararg arguments: String) = runner(*arguments).build()

  private fun testReport(
    task: String = "test",
    testClass: String = "ImageTest",
  ) = directory.resolve("build/test-results/$task/TEST-$testClass.xml").toFile()
}
