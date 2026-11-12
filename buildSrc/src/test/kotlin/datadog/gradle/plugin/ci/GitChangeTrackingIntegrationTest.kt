package datadog.gradle.plugin.ci

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GitChangeTrackingIntegrationTest : GradleFixture() {
  @BeforeEach
  fun setUp() {
    writeSettings(
      """
      rootProject.name = "git-change-tracking-test"
      include(":inst", ":other", ":common", ":user")
      """,
    )
    writeRootProject(
      """
      import datadog.gradle.plugin.ci.testAggregate

      plugins {
        id("dd-trace-java.ci-jobs")
      }

      testAggregate("ci", listOf(":"))
      """,
    )
    // Compiles its test sources twice, like the instrumentation latest dependency suites
    writeFile(
      "inst/build.gradle.kts",
      """
      plugins {
        java
      }

      val latestDepTest by sourceSets.creating {
        java.setSrcDirs(listOf("src/test/java"))
      }
      val latestDepTestTask = tasks.register<Test>("latestDepTest") {
        testClassesDirs = latestDepTest.output.classesDirs
        classpath = latestDepTest.runtimeClasspath
      }
      tasks.register("allTests") { dependsOn(tasks.test) }
      tasks.register("allLatestDepTests") { dependsOn(latestDepTestTask) }
      """,
    )
    writeFile(
      "other/build.gradle.kts",
      """
      plugins {
        java
      }

      tasks.register("allTests") { dependsOn(tasks.test) }
      """,
    )
    writeFile("common/build.gradle.kts", "")
    // Compiles test sources located in another project
    writeFile(
      "user/build.gradle.kts",
      """
      plugins {
        java
      }

      sourceSets.test {
        java.srcDir(rootDir.resolve("common/src/test/java"))
      }
      tasks.register("allTests") { dependsOn(tasks.test) }
      """,
    )
    writeJavaSource("InstTest", "class InstTest {}", sourceSet = "test", projectPath = "inst")
    writeJavaSource("Moved", "class Moved {}", projectPath = "other")
    writeJavaSource("SharedTest", "class SharedTest {}", sourceSet = "test", projectPath = "common")
    initGitRepo()
  }

  @Test
  fun `test-only change selects the latest dependency suite compiling the test sources`() {
    writeJavaSource("InstTest", "class InstTest { void added() {} }", sourceSet = "test", projectPath = "inst")
    commitAll("Change a test")

    val output = runChangeTracking().output

    assertThat(output)
      .contains("Selecting :inst:allTests")
      .contains("Selecting :inst:allLatestDepTests")
      .contains("Skipping :other:allTests")
      .contains("Skipping :user:allTests")
  }

  @Test
  fun `change in test sources compiled by another project selects that project`() {
    writeJavaSource("SharedTest", "class SharedTest { void added() {} }", sourceSet = "test", projectPath = "common")
    commitAll("Change a shared test")

    val output = runChangeTracking().output

    assertThat(output)
      .contains("Selecting :user:allTests")
      .contains("Skipping :inst:allTests")
      .contains("Skipping :other:allTests")
  }

  @Test
  fun `moved file selects the project it moved out of`() {
    file("inst/src/main/java").mkdirs()
    exec("git", "mv", "other/src/main/java/Moved.java", "inst/src/main/java/Moved.java")
    commitAll("Move a class")

    val output = runChangeTracking().output

    assertThat(output)
      .contains("Selecting :other:allTests")
      .contains("Selecting :inst:allTests")
      .contains("Selecting :inst:allLatestDepTests")
      .contains("Skipping :user:allTests")
  }

  private fun runChangeTracking(): BuildResult =
    run("ciTest", "ciLatestDepTest", "--dry-run", "-PgitBaseRef=HEAD~1")

  private fun commitAll(message: String) {
    exec("git", "add", "-A")
    exec("git", "commit", "-m", message)
  }
}
