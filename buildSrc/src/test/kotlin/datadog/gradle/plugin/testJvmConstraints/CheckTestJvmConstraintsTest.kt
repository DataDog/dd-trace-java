package datadog.gradle.plugin.testJvmConstraints

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

class CheckTestJvmConstraintsTest : GradleFixture() {
  @Test
  fun `passes on satisfiable constraints and runs with check`() {
    writeTestProject(
      """
      tasks.named<Test>("test") {
        extensions.configure<TestJvmConstraintsExtension> {
          minJavaVersion = JavaVersion.VERSION_1_8
          maxJavaVersion = JavaVersion.VERSION_1_8
          includeJdk.add("ORACLE8")
        }
      }
      tasks.register<Test>("latestDepTest") {
        extensions.configure<TestJvmConstraintsExtension> {
          minJavaVersion = JavaVersion.VERSION_17
        }
      }
      tasks.register<Test>("forcedTest") {
        extensions.configure<TestJvmConstraintsExtension> {
          minJavaVersion = JavaVersion.VERSION_11
          maxJavaVersion = JavaVersion.VERSION_1_8
          forceJdk.add("ZULU8")
        }
      }
      tasks.register<Test>("aliasTest") {
        extensions.configure<TestJvmConstraintsExtension> {
          minJavaVersion = JavaVersion.VERSION_11
          includeJdk.add("CUSTOM")
        }
      }
      """
    )

    val result = run("check")

    assertThat(result.task(":${CheckTestJvmConstraints.TASK_NAME}")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `fails on unsatisfiable constraints`() {
    writeTestProject(
      """
      testJvmConstraints {
        maxJavaVersion = JavaVersion.VERSION_1_8
      }

      tasks.named<Test>("test") {
        extensions.configure<TestJvmConstraintsExtension> {
          maxJavaVersion = JavaVersion.VERSION_17
          minJavaVersion = JavaVersion.VERSION_11
          includeJdk.add("ORACLE8")
        }
      }
      tasks.register<Test>("latestDepTest") {
        extensions.configure<TestJvmConstraintsExtension> {
          minJavaVersion = JavaVersion.VERSION_11
        }
      }
      tasks.register<Test>("aliasTest") {
        extensions.configure<TestJvmConstraintsExtension> {
          minJavaVersion = JavaVersion.VERSION_11
          includeJdk.add("CUSTOM")
        }
      }
      tasks.register<Test>("tipTest") {
        extensions.configure<TestJvmConstraintsExtension> {
          maxJavaVersion = JavaVersion.VERSION_17
          includeJdk.add("tip")
        }
      }
      tasks.register<Test>("forcedTipTest") {
        extensions.configure<TestJvmConstraintsExtension> {
          minJavaVersion = JavaVersion.VERSION_11
          forceJdk.add("tip")
        }
      }
      """
    )

    val result = run(CheckTestJvmConstraints.TASK_NAME, expectFailure = true)

    assertThat(result.task(":${CheckTestJvmConstraints.TASK_NAME}")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains("  - :test: minJavaVersion=11, maxJavaVersion=17, includeJdk=[ORACLE8]")
      .contains("  - :latestDepTest: minJavaVersion=11, maxJavaVersion=1.8")
      .contains("  - :aliasTest: minJavaVersion=11, maxJavaVersion=1.8, includeJdk=[CUSTOM]")
      .contains("  - :tipTest: maxJavaVersion=17, includeJdk=[tip]")
      .contains("  - :forcedTipTest: minJavaVersion=11, maxJavaVersion=1.8, forceJdk=[tip]")
  }

  @Test
  fun `passes when coverage has a coverage test JVM`() {
    writeTestProject(
      """
      tasks.named<Test>("test") {
        extensions.configure<TestJvmConstraintsExtension> {
          maxJavaVersion = JavaVersion.VERSION_1_8
          includeJdk.add("ORACLE8")
          coverageTestJvm = "ORACLE8"
        }
      }
      """,
      plugins = "jacoco",
    )

    val result = run(CheckTestJvmConstraints.TASK_NAME)

    assertThat(result.task(":${CheckTestJvmConstraints.TASK_NAME}")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `fails when coverage has no LTS version and no coverage test JVM`() {
    writeTestProject(
      """
      tasks.named<Test>("test") {
        extensions.configure<TestJvmConstraintsExtension> {
          maxJavaVersion = JavaVersion.VERSION_1_8
          includeJdk.add("ORACLE8")
        }
      }
      """,
      plugins = "jacoco",
    )

    val result = run(CheckTestJvmConstraints.TASK_NAME, expectFailure = true)

    assertThat(result.task(":${CheckTestJvmConstraints.TASK_NAME}")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains(":test: no Java LTS version satisfies its testJvmConstraints, so its coverage is never checked in CI.")
  }

  @Test
  fun `fails when the coverage test JVM is not allowed`() {
    writeTestProject(
      """
      testJvmConstraints {
        minJavaVersion = JavaVersion.VERSION_11
        coverageTestJvm = "8"
      }
      """,
      plugins = "jacoco",
    )

    val result = run(CheckTestJvmConstraints.TASK_NAME, expectFailure = true)

    assertThat(result.task(":${CheckTestJvmConstraints.TASK_NAME}")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains(":test: its coverageTestJvm=8 is not allowed by its testJvmConstraints, so its coverage is never checked in CI.")
  }

  private fun writeTestProject(configuration: String, plugins: String = "") {
    writeSettings("""rootProject.name = "check-test-jvm-constraints"""")
    writeRootProject(
      """
      import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension

      plugins {
        id("dd-trace-java.test-jvm-constraints")
        $plugins
      }

      """.trimIndent() + "\n" + configuration.trimIndent()
    )
  }
}
