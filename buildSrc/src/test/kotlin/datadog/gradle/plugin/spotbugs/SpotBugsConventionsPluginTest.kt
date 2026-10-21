package datadog.gradle.plugin.spotbugs

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.SKIPPED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SpotBugsConventionsPluginTest : GradleFixture() {
  @BeforeEach
  fun setup() {
    writeSettings("""rootProject.name = "spotbugs-convention"""")
    writeFile(
      "gradle/libs.versions.toml",
      """
      [libraries]
      spotbugs-annotations = { module = "com.github.spotbugs:spotbugs-annotations", version = "4.10.4" }
      jsr305 = { module = "com.google.code.findbugs:jsr305", version = "3.0.2" }
      """
    )
    writeFile("gradle/spotbugFilters/exclude.xml", "<FindBugsFilter/>")
    writeRootProject(
      """
      import com.github.spotbugs.snom.SpotBugsTask

      plugins { id("dd-trace-java.conventions.spotbugs") }
      allprojects { repositories { mavenCentral() } }
      tasks.withType<JavaCompile>().configureEach { options.release = 8 }
      """
    )
  }

  @ParameterizedTest
  @ValueSource(strings = ["main", "main_java6", "main_java11", "main_java17", "main_java21", "main_java25"])
  fun `production violations fail check and appear in the HTML report`(sourceSet: String) {
    writeRootProject("""sourceSets.maybeCreate("$sourceSet")""", append = true)
    writeViolation(sourceSet)

    val result = run("check", expectFailure = true)
    val taskName = "spotbugs" + sourceSet.replaceFirstChar(Char::uppercaseChar)

    assertThat(result.task(":$taskName")?.outcome).describedAs(result.output).isEqualTo(FAILED)
    assertThat(buildFile("reports/spotbugs/$taskName.html"))
      .exists().content().contains("ES_COMPARING_PARAMETER_STRING_WITH_EQ")
  }

  @Test
  fun `test benchmark and smoke sources are not analysed`() {
    val sourceSets = listOf("test", "integrationTest", "test_java17", "jmh", "otherMain")
    sourceSets.forEach {
      writeRootProject("""sourceSets.maybeCreate("$it")""", append = true)
      writeViolation(it)
    }
    addSubproject(
      "dd-smoke-tests:application",
      """
      plugins { id("dd-trace-java.conventions.spotbugs") }
      sourceSets.create("main_java17")
      """
    )
    writeViolation("main", ":dd-smoke-tests:application")
    writeViolation("main_java17", ":dd-smoke-tests:application")
    val taskPaths = sourceSets.map { ":spotbugs" + it.replaceFirstChar(Char::uppercaseChar) } +
      listOf(
        ":dd-smoke-tests:application:spotbugsMain",
        ":dd-smoke-tests:application:spotbugsMain_java17"
      )

    val result = run(*taskPaths.toTypedArray())

    taskPaths.forEach {
      assertThat(result.task(it)?.outcome).describedAs("$it\n${result.output}").isEqualTo(SKIPPED)
    }
  }

  @Test
  fun `module analysis exclusions apply to existing and later production tasks`() {
    writeRootProject(
      """
      tasks.named("spotbugsMain").get()
      spotbugs { onlyAnalyze = listOf("none") }
      sourceSets.create("main_java11")
      """,
      append = true
    )
    writeViolation("main")
    writeViolation("main_java11")

    val result = run("spotbugsMain", "spotbugsMain_java11")

    listOf("spotbugsMain", "spotbugsMain_java11").forEach {
      assertThat(result.task(":$it")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
      assertThat(buildFile("reports/spotbugs/$it.html"))
        .exists().content().doesNotContain("ES_COMPARING_PARAMETER_STRING_WITH_EQ")
    }
  }

  @Test
  fun `test rules can change independently of production rules`() {
    writeRootProject(
      """
      tasks.named<SpotBugsTask>("spotbugsTest") {
        enabled = true
        visitors = listOf("FindNullDeref")
      }
      """,
      append = true
    )
    writeViolation("main")
    writeJavaSource(
      "example.NullDereference",
      """
      package example;
      public class NullDereference {
        public int length() {
          String value = null;
          return value.length();
        }
      }
      """,
      sourceSet = "test"
    )

    val nullCheck = run("spotbugsMain", "spotbugsTest", "--continue", expectFailure = true)

    assertThat(nullCheck.task(":spotbugsMain")?.outcome).describedAs(nullCheck.output).isEqualTo(FAILED)
    assertThat(nullCheck.task(":spotbugsTest")?.outcome).describedAs(nullCheck.output).isEqualTo(FAILED)
    assertThat(buildFile("reports/spotbugs/spotbugsTest.html"))
      .exists().content().contains("NP_ALWAYS_NULL")

    writeRootProject(
      """
      tasks.named<SpotBugsTask>("spotbugsTest") {
        visitors = listOf("FindOpenStream")
      }
      """,
      append = true
    )

    val resourceCheck = run("spotbugsMain", "spotbugsTest", "--continue", expectFailure = true)

    assertThat(resourceCheck.task(":spotbugsMain")?.outcome)
      .describedAs(resourceCheck.output).isEqualTo(FAILED)
    assertThat(resourceCheck.task(":spotbugsTest")?.outcome)
      .describedAs(resourceCheck.output).isEqualTo(SUCCESS)
    assertThat(buildFile("reports/spotbugs/spotbugsTest.html"))
      .exists().content().doesNotContain("NP_ALWAYS_NULL")
  }

  private fun writeViolation(sourceSet: String, projectPath: String? = null) {
    writeJavaSource(
      "example.StringComparison",
      """
      package example;
      public class StringComparison {
        public boolean same(String left, String right) {
          return left == right;
        }
      }
      """,
      sourceSet,
      projectPath
    )
  }
}
