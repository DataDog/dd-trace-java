package datadog.gradle.plugin.ci

import datadog.gradle.plugin.GradleFixture
import datadog.gradle.plugin.testJvmConstraints.CHECK_COVERAGE_ON_FIRST_TEST_JVM
import datadog.gradle.plugin.testJvmConstraints.isLongTermSupport
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.JavaVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class TestAggregateCoverageTest : GradleFixture() {
  @Test
  fun `aggregate wires the coverage of projects whose coverage test JVM is the requested test JVM`() {
    val current = JavaVersion.current().majorVersion
    assumeTrue(isLongTermSupport(current.toInt()) && current != "8", "The current JVM must be an LTS version other than 8")
    writeSettings("""rootProject.name = "test-aggregate-coverage"""")
    writeRootProject(
      """
      import datadog.gradle.plugin.ci.testAggregate

      plugins {
        id("dd-trace-java.ci-jobs")
      }

      testAggregate("base", listOf(":"))
      """
    )
    // Coverage test JVM is 8
    addSubproject("lowest", subprojectBuildScript(""))
    // Coverage test JVM is the current JVM
    addSubproject("current", subprojectBuildScript("testJvmConstraints { minJavaVersion = JavaVersion.toVersion($current) }"))

    val result = run("baseTest", "-PtestJvm=$current", "-P$CHECK_COVERAGE_ON_FIRST_TEST_JVM", "--dry-run")

    assertThat(result.output)
      .contains(":current:jacocoTestCoverageVerification SKIPPED")
      .contains(":lowest:test SKIPPED")
      .doesNotContain(":lowest:jacocoTestCoverageVerification")
  }

  private fun subprojectBuildScript(constraints: String) =
    """
    plugins {
      id("dd-trace-java.test-jvm-constraints")
      jacoco
    }

    $constraints

    tasks.register("allTests") {
      dependsOn(tasks.named("test"))
    }
    """
}
