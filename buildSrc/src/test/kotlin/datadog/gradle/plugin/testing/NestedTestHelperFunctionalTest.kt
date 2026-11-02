package datadog.gradle.plugin.testing

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NestedTestHelperFunctionalTest : GradleFixture() {
  @Test
  fun `forked discovery preserves nested and inherited tests with configuration cache reuse`() {
    writeSettings("rootProject.name = \"nested-helper-fixture\"")
    writeRootProject(
      """
      import datadog.gradle.plugin.testing.NestedTestHelperSpec

      plugins {
        java
        // Makes the buildSrc plugin classpath available to this fixture's script.
        id("dd-trace-java.version-file")
      }
      repositories { mavenCentral() }
      dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.1")
      }
      tasks.register<Test>("forkedTest") {
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform()
        setIncludes(listOf("**/*ForkedTest*"))
        forkEvery = 1
        exclude(NestedTestHelperSpec(tasks.named<Test>(name).flatMap { it.classpath.elements }))
      }
      """,
    )
    writeJavaSource(
      "ExampleForkedTest",
      """
      import org.junit.jupiter.api.Test;
      import org.junit.jupiter.api.Nested;
      import static org.junit.jupiter.api.Assertions.assertEquals;

      class ExampleForkedTest {
        static class Helper { int value() { return 42; } }
        @Test void usesHelper() { assertEquals(42, new Helper().value()); }
        @Nested class InnerTest { @Test void nestedTest() {} }
        static class InheritedTest extends BaseTests {}
      }
      class BaseTests { @Test void inheritedTest() {} }
      """,
      sourceSet = "test",
    )

    repeat(2) { iteration ->
      val result = run("forkedTest", "--rerun-tasks", "--configuration-cache", "--info")
      assertThat(result.output).contains("BUILD SUCCESSFUL")
      if (iteration == 1) assertThat(result.output).contains("Reusing configuration cache.")
      val reports = file("build/test-results/forkedTest").listFiles { f -> f.extension == "xml" }.orEmpty()
      val testNames = reports.flatMap { report ->
        val cases = parseXml(report).getElementsByTagName("testcase")
        (0 until cases.length).map { cases.item(it).attributes.getNamedItem("name").nodeValue }
      }
      assertThat(testNames).containsExactlyInAnyOrder("usesHelper()", "nestedTest()", "inheritedTest()")
      // Outer class, @Nested class and static inherited-test class remain independent candidates.
      assertThat(Regex("Starting process 'Gradle Test Executor").findAll(result.output).count()).isEqualTo(3)
    }
  }
}
