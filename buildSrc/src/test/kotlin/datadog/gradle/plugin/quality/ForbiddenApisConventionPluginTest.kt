package datadog.gradle.plugin.quality

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class ForbiddenApisConventionPluginTest : GradleFixture() {
  @BeforeEach
  fun setup() {
    writeSettings("""rootProject.name = "forbidden-apis-convention-test"""")
    writeFile("gradle/libs.versions.toml", File("../gradle/libs.versions.toml").readText())
    writeFile(
      "gradle/forbiddenApiFilters/main.txt",
      """
      java.lang.System#exit(int)
      missing.OptionalDependency
      """
    )
  }

  @Test
  fun `preserves defaults and name-based exclusions for late source sets and tasks`() {
    addSubproject(
      "library",
      """
      import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis

      plugins {
        id("dd-trace-java.conventions.forbidden-apis")
      }

      sourceSets.create("latestDepTest")
      sourceSets.create("main_java11")
      sourceSets.create("testFixtures")
      sourceSets.create("jmh")
      tasks.register("forbiddenApisSyntheticTest")
      tasks.register("unrelatedTest")

      tasks.register("verifyDefaults") {
        doLast {
          val mainFilter = rootProject.file("gradle/forbiddenApiFilters/main.txt")
          tasks.withType<CheckForbiddenApis>().forEach {
            check(it.signaturesFiles.files == setOf(mainFilter))
            check(it.ignoreSignaturesOfMissingClasses)
            check(!it.ignoreFailures)
          }
          listOf("forbiddenApisTest", "forbiddenApisLatestDepTest", "forbiddenApisSyntheticTest").forEach {
            check(!tasks.getByName(it).enabled)
          }
          listOf("forbiddenApisMain", "forbiddenApisMain_java11", "forbiddenApisTestFixtures", "forbiddenApisJmh", "unrelatedTest").forEach {
            check(tasks.getByName(it).enabled)
          }
          val aggregate = tasks.getByName("forbiddenApis")
          val checkTask = tasks.getByName("check")
          check(aggregate in checkTask.taskDependencies.getDependencies(checkTask))
          check(tasks.getByName("forbiddenApisMain_java11") in aggregate.taskDependencies.getDependencies(aggregate))
          val dependency = configurations.getByName("compileOnly").dependencies.single {
            it.group == "de.thetaphi" && it.name == "forbiddenapis"
          }
          check(dependency.version == libs.versions.forbiddenapis.get())
          check(configurations.getByName("runtimeClasspath").allDependencies.none { it.name == "forbiddenapis" })
        }
      }
      """
    )

    val result = run(":library:verifyDefaults")

    assertThat(result.task(":library:verifyDefaults")?.outcome)
      .describedAs(result.output).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `preserves extension and typed task overrides`() {
    writeFile("gradle/forbiddenApiFilters/instrumentation.txt", "java.lang.Runtime#exit(int)")
    writeRootProject(
      """
      import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis

      plugins {
        id("dd-trace-java.conventions.forbidden-apis")
      }

      forbiddenApis {
        signaturesFiles += files("gradle/forbiddenApiFilters/instrumentation.txt")
        ignoreFailures = true
      }
      tasks.named<CheckForbiddenApis>("forbiddenApisMain") {
        failOnMissingClasses = false
      }
      tasks.named("forbiddenApisTest") {
        enabled = true
      }

      tasks.register("verifyOverrides") {
        doLast {
          val main = tasks.named<CheckForbiddenApis>("forbiddenApisMain").get()
          check(main.signaturesFiles.files == setOf(file("gradle/forbiddenApiFilters/main.txt"), file("gradle/forbiddenApiFilters/instrumentation.txt")))
          check(main.ignoreFailures)
          check(!main.failOnMissingClasses)
          check(tasks.getByName("forbiddenApisTest").enabled)
        }
      }
      """
    )

    val result = run("verifyOverrides")

    assertThat(result.task(":verifyOverrides")?.outcome)
      .describedAs(result.output).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `fails check on a forbidden call and allows compile-only suppression`() {
    writeRootProject(
      """
      plugins {
        id("dd-trace-java.conventions.forbidden-apis")
      }
      repositories {
        mavenCentral()
      }
      """
    )
    writeJavaSource("Example", "public class Example { public void exit() { System.exit(0); } }")

    val failed = run("check", expectFailure = true)

    assertThat(failed.task(":forbiddenApisMain")?.outcome)
      .describedAs(failed.output).isEqualTo(TaskOutcome.FAILED)
    assertThat(failed.output).contains("java.lang.System#exit(int)")

    writeJavaSource(
      "Example",
      """
      import de.thetaphi.forbiddenapis.SuppressForbidden;

      public class Example {
        @SuppressForbidden
        public void exit() { System.exit(0); }
      }
      """
    )

    val suppressed = run("check")

    assertThat(suppressed.task(":forbiddenApisMain")?.outcome)
      .describedAs(suppressed.output).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(suppressed.task(":forbiddenApisTest")?.outcome).isEqualTo(TaskOutcome.SKIPPED)
    assertThat(suppressed.task(":check")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }
}
