package datadog.buildlogic.testcontainers

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

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
    assertThat(report()).contains("library/cassandra@${registry.digest}")
    val firstRequests = registry.requestCount
    val warm = run("test")
    assertThat(warm.output).contains("Reusing configuration cache")
    assertThat(warm.task(":test")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(registry.requestCount).isGreaterThan(firstRequests)

    registry.imageVersion = 2
    val changed = run("test")
    assertThat(changed.output).contains("Reusing configuration cache")
    assertThat(changed.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(report()).contains("library/cassandra@${registry.digest}")

    run("clean")
    val restored = run("test")
    assertThat(restored.output).contains("Reusing configuration cache")
    assertThat(restored.task(":test")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    assertThat(report()).contains("library/cassandra@${registry.digest}")

    assertThat(run("latestDepTest", "latestDepTestForkedTest").task(":latestDepTestForkedTest")?.outcome)
      .isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    assertThat(directory.resolve("build/test-results/latestDepTest/TEST-ImageTest.xml").toFile().readText())
      .contains("library/cassandra@${registry.digest}")
    assertThat(run("declaredTest").task(":declaredTest")?.outcome).isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    assertThat(directory.resolve("build/test-results/declaredTest/TEST-ImageTest.xml").toFile().readText())
      .contains("library/cassandra@${registry.digest}")
    val requestsBeforeUnrelatedSuite = registry.requestCount
    assertThat(run("isolatedTest").task(":isolatedTest")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(run("emptyTest").task(":emptyTest")?.outcome).isEqualTo(TaskOutcome.NO_SOURCE)
    assertThat(registry.requestCount).isEqualTo(requestsBeforeUnrelatedSuite)

    registry.unavailable = true
    assertThat(runner("test").buildAndFail().output)
      .contains("Cannot resolve test container image")
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
    assertThat(report()).contains("mirror.example/team/cassandra@$digest")
    val changed =
      runner("test")
        .withEnvironment(
          System.getenv() +
            ("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX" to "another.example/team/"),
        ).build()
    assertThat(changed.task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(report()).contains("another.example/team/cassandra@$digest")

    directory.resolve("build.gradle.kts").toFile().appendText(
      """

      sourceSets.test { resources.setSrcDirs(listOf("lateResources")) }
      tasks.named<Test>("test") {
        environment("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX", "task.example/")
      }
      """.trimIndent(),
    )
    assertThat(run("test").task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(report()).contains("task.example/cassandra@$digest")
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
    assertThat(report()).contains("registry-1.docker.io/library/cassandra@$digest")
    val reused = runner("test").withEnvironment(environment).build()
    assertThat(reused.output).contains("Reusing configuration cache")
    assertThat(reused.task(":test")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `Groovy dependency extension methods are available for each source set`() {
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
    assertThat(report()).contains("registry-1.docker.io/library/$image")
    assertThat(directory.resolve("build/test-results/integrationTest/TEST-ImageTest.xml").toFile().readText())
      .contains("registry-1.docker.io/library/$image")
    val reused = run("test", "integrationTest")
    assertThat(reused.output).contains("Reusing configuration cache")
    assertThat(reused.task(":test")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    assertThat(reused.task(":integrationTest")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `bearer authentication resolves manifests with an older HttpClient in buildSrc`() {
    registry.requireAuthentication = true
    fixture(registry.image)
    // Reproduce the parent classloader supplied by Aether in the real buildSrc.
    val httpClasspath =
      System.getProperty("test.buildSrc.classpath").split(File.pathSeparator).joinToString(", ") { "\"$it\"" }
    Files.createDirectories(directory.resolve("buildSrc/src/main/java"))
    directory.resolve("buildSrc/src/main/java/BuildLogic.java").toFile().writeText("public class BuildLogic {}\n")
    directory.resolve("buildSrc/build.gradle.kts").toFile().writeText(
      """
      plugins { java }
      dependencies { implementation(files($httpClasspath)) }
      """.trimIndent(),
    )
    // Load as an included build instead of using TestKit's injected plugin classpath.
    val metadata = Properties()
    javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use { metadata.load(it) }
    val pluginClasspath = metadata.getProperty("implementation-classpath").split(File.pathSeparator).joinToString(", ") { "\"$it\"" }
    Files.createDirectories(directory.resolve("plugin"))
    directory.resolve("plugin/settings.gradle.kts").toFile().writeText("rootProject.name = \"fixture-plugin\"\n")
    directory.resolve("plugin/build.gradle.kts").toFile().writeText(
      """
      plugins { `java-gradle-plugin` }
      dependencies { implementation(files($pluginClasspath)) }
      gradlePlugin {
        plugins {
          create("testcontainers") {
            id = "dd-trace-java.testcontainers"
            implementationClass = "datadog.buildlogic.testcontainers.TestcontainersPlugin"
          }
        }
      }
      """.trimIndent(),
    )
    val settings = directory.resolve("settings.gradle.kts").toFile()
    settings.writeText("pluginManagement { includeBuild(\"plugin\") }\n" + settings.readText())
    assertThat(runner("test", injectPluginClasspath = false).build().task(":test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(registry.tokenRequests.get()).isPositive()
    assertThat(registry.authorizedRequests.get()).isPositive()
    assertThat(report()).contains(registry.digest)
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
      import datadog.buildlogic.testcontainers.containerImage
      import datadog.buildlogic.testcontainers.image
      import datadog.buildlogic.testcontainers.testContainerImage

      plugins {
        java
        id("dd-trace-java.testcontainers")
      }
      val latestDepTest = sourceSets.create("latestDepTest") {
        java.setSrcDirs(sourceSets.test.get().java.srcDirs)
      }
      val declaredTest = sourceSets.create("declaredTest") {
        java.setSrcDirs(sourceSets.test.get().java.srcDirs)
      }
      val isolatedTest = sourceSets.create("isolatedTest")
      val emptyTest = sourceSets.create("emptyTest")
      tasks.register<Test>("latestDepTest") {
        testClassesDirs = latestDepTest.output.classesDirs
        classpath = latestDepTest.runtimeClasspath
      }
      tasks.register<Test>("latestDepTestForkedTest") {
        testClassesDirs = latestDepTest.output.classesDirs
        classpath = latestDepTest.runtimeClasspath
      }
      tasks.register<Test>("declaredTest") {
        testClassesDirs = declaredTest.output.classesDirs
        classpath = declaredTest.runtimeClasspath
      }
      tasks.register<Test>("isolatedTest") {
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
      }
      // Plugins must see declarations and inheritance added after tasks are realized.
      tasks.named("test").get()
      tasks.named("latestDepTest").get()
      tasks.named("latestDepTestForkedTest").get()
      configurations.named(latestDepTest.implementationConfigurationName) {
        extendsFrom(configurations.testImplementation.get())
      }
      configurations.named(emptyTest.implementationConfigurationName) {
        extendsFrom(configurations.testImplementation.get())
      }
      dependencies {
        testImplementation(files(${junitClasspath()}))
        add(declaredTest.implementationConfigurationName, files(${junitClasspath()}))
        add(isolatedTest.implementationConfigurationName, files(${junitClasspath()}))
        testContainerImage(image("$image", "test.cassandra.image"))
        containerImage("declaredTest", image("$image", "test.cassandra.image"))
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
        }
      }
      """.trimIndent(),
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

  private fun runner(
    vararg arguments: String,
    injectPluginClasspath: Boolean = true,
  ) = GradleRunner
    .create()
    .withProjectDir(directory.toFile())
    .apply { if (injectPluginClasspath) withPluginClasspath() }
    .withArguments(
      *arguments,
      "--build-cache",
      "--configuration-cache",
      "--stacktrace",
      "--max-workers=2",
      "-Dorg.gradle.jvmargs=-Xmx512m",
    )

  private fun run(vararg arguments: String) = runner(*arguments).build()

  private fun report() = directory.resolve("build/test-results/test/TEST-ImageTest.xml").toFile().readText()
}
