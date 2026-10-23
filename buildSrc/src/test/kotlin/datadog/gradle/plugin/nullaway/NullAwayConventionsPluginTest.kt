package datadog.gradle.plugin.nullaway

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NullAwayConventionsPluginTest : GradleFixture() {
  @BeforeEach
  fun setup() {
    writeSettings("""rootProject.name = "nullaway-convention"""")
    writeFile(
      "gradle/libs.versions.toml",
      """
      [libraries]
      errorprone = { module = "com.google.errorprone:error_prone_core", version = "2.50.0" }
      nullaway = { module = "com.uber.nullaway:nullaway", version = "0.14.2" }
      """
    )
    writeRootProject(
      """
      plugins { id("dd-trace-java.conventions.nullaway") }
      repositories { mavenCentral() }
      tasks.withType<JavaCompile>().configureEach { options.release = 8 }
      """
    )
  }

  @ParameterizedTest
  @ValueSource(strings = ["main", "main_java17"])
  fun `production nullness violations fail compilation`(sourceSet: String) {
    writeRootProject("""sourceSets.maybeCreate("$sourceSet")""", append = true)
    writeViolation(sourceSet)

    val taskName = if (sourceSet == "main") "compileJava" else "compileMain_java17Java"
    val result = run(taskName, expectFailure = true)

    assertThat(result.task(":$taskName")?.outcome).describedAs(result.output).isEqualTo(FAILED)
    assertThat(result.output).contains("[NullAway]")
  }

  @Test
  fun `compliant code compiles while other Error Prone checks stay disabled`() {
    writeJavaSource(
      "datadog.example.Compliant",
      """
      package datadog.example;
      public class Compliant {
        public String value() {
          new IllegalStateException();
          return "value";
        }
      }
      """
    )

    val result = run("compileJava", "--configuration-cache")

    assertThat(result.task(":compileJava")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(result.output).doesNotContain("[DeadException]")
    assertThat(run("compileJava", "--configuration-cache").output).contains("Reusing configuration cache")
  }

  @Test
  fun `test and benchmark sources are not checked`() {
    writeRootProject("""sourceSets.create("jmh")""", append = true)
    writeViolation("test")
    writeViolation("jmh")

    val result = run("compileTestJava", "compileJmhJava")

    assertThat(result.task(":compileTestJava")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(result.task(":compileJmhJava")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
  }

  @Test
  fun `an older production compiler still compiles without the checker`() {
    writeRootProject(
      """
      tasks.compileJava {
        javaCompiler.set(javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(11) })
      }
      """,
      append = true
    )
    writeViolation("main")

    val result = run("compileJava")

    assertThat(result.task(":compileJava")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
  }

  @Test
  fun `generated sources are not checked`() {
    writeRootProject(
      """sourceSets.main { java.srcDir(layout.buildDirectory.dir("generated/sources")) }""",
      append = true
    )
    writeFile(
      "build/generated/sources/datadog/example/Generated.java",
      """
      package datadog.example;
      public class Generated {
        public String value() { return null; }
      }
      """
    )

    val result = run("compileJava")

    assertThat(result.task(":compileJava")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
  }

  private fun writeViolation(sourceSet: String) {
    writeJavaSource(
      "datadog.example.Violation",
      """
      package datadog.example;
      public class Violation {
        public String value() { return null; }
      }
      """,
      sourceSet
    )
  }
}
