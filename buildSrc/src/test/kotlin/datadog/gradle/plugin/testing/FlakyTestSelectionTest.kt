package datadog.gradle.plugin.testing

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.BeforeEach
import org.tabletest.junit.TableTest
import java.io.File
import java.util.Properties

class FlakyTestSelectionTest : GradleFixture() {
  @BeforeEach
  fun setup() {
    // Load the build logic so the fixture exercises the shared selection policy.
    val metadata = Properties().apply {
      FlakyTestSelectionTest::class.java.getResourceAsStream("/plugin-under-test-metadata.properties")!!.use { load(it) }
    }
    val classpath = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
      .joinToString(", ") { "\"${it.replace("\\", "\\\\")}\"" }
    writeFile(
      "settings.gradle.kts",
      """
      rootProject.name = "flaky-test-selection"
      """
    )
    writeFile(
      "build.gradle.kts",
      """
      import datadog.gradle.plugin.testing.configureFlakyTestSelection

      buildscript {
        dependencies {
          classpath(files($classpath))
        }
      }
      plugins { java }
      repositories { mavenCentral() }
      dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
      }
      for (taskName in listOf("forkedTest", "unfilteredTest")) {
        tasks.register<Test>(taskName) {
          testClassesDirs = sourceSets.test.get().output.classesDirs
          classpath = sourceSets.test.get().runtimeClasspath
        }
      }
      tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        if (name == "forkedTest") {
          include("**/*ForkedTest*")
        } else if (name == "test") {
          exclude("**/*ForkedTest*")
        }
        configureFlakyTestSelection(
          providers.gradleProperty("skipFlakyTests").isPresent,
          providers.gradleProperty("runFlakyTests").isPresent
        )
      }
      """
    )
  }

  @TableTest(
    """
    scenario                                  | task           | name
    non-forked suite excludes forked classes  | test           | ExampleTest
    forked suite includes only forked classes | forkedTest     | ExampleForkedTest
    suite has no class-name filters           | unfilteredTest | ExampleTest
    """
  )
  fun `flaky-only run succeeds after the last flaky tag is removed`(task: String, name: String) {
    writeTest(name, "@org.junit.jupiter.api.Tag(\"flaky\")")
    val initial = run(task, "-PrunFlakyTests", unsetEnv = setOf("CI_USE_TEST_AGENT"), pluginClasspath = emptyList())
    assertThat(initial.task(":$task")?.outcome).describedAs(initial.output).isEqualTo(SUCCESS)
    assertThat(buildFile("test-results/$task/TEST-$name.xml").readText()).contains("tests=\"1\"")

    writeTest(name)
    val empty = run(task, "-PrunFlakyTests", unsetEnv = setOf("CI_USE_TEST_AGENT"), pluginClasspath = emptyList())
    assertThat(empty.task(":$task")?.outcome).describedAs(empty.output).isEqualTo(SUCCESS)
    assertThat(buildFile("test-results/$task/TEST-$name.xml")).doesNotExist()
  }

  @TableTest(
    """
    scenario                                            | skipFlaky | runFlaky
    default run requires discovered tests               | false     | false
    skipping flaky tests preserves discovery checks     | true      | false
    skipping takes precedence over flaky-only selection | true      | true
    """
  )
  fun `other run modes still fail when no tests are discovered`(skipFlaky: Boolean, runFlaky: Boolean) {
    writeJavaSource("Helper", "public class Helper {}", sourceSet = "test")
    val properties = listOfNotNull(
      "-PskipFlakyTests".takeIf { skipFlaky },
      "-PrunFlakyTests".takeIf { runFlaky }
    )
    val result = run("test", *properties.toTypedArray(), expectFailure = true, pluginClasspath = emptyList())
    assertThat(result.task(":test")?.outcome).describedAs(result.output).isEqualTo(FAILED)
    assertThat(result.output).contains("did not discover any tests")
  }

  private fun writeTest(name: String, tag: String = "") {
    writeJavaSource(
      name,
      """
      $tag
      public class $name {
        @org.junit.jupiter.api.Test
        void passes() {}
      }
      """,
      sourceSet = "test"
    )
  }
}
