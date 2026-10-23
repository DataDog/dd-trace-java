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
      jspecify = { module = "org.jspecify:jspecify", version = "1.0.1" }
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

  @Test
  fun `JSpecify mode checks nullable type arguments`() {
    useJSpecify()
    writeJavaSource(
      "datadog.example.GenericContract",
      """
      package datadog.example;
      import java.util.List;
      import org.jspecify.annotations.NullMarked;
      import org.jspecify.annotations.Nullable;
      @NullMarked
      public class GenericContract {
        public List<String> values(List<@Nullable String> values) { return values; }
      }
      """
    )

    val result = run("compileJava", expectFailure = true)

    assertThat(result.task(":compileJava")?.outcome).describedAs(result.output).isEqualTo(FAILED)
    assertThat(result.output).contains("[NullAway]")
  }

  @ParameterizedTest
  @ValueSource(strings = ["@Nullable String[]", "@Nullable String..."])
  fun `JSpecify checks nullable array and varargs elements`(parameterType: String) {
    useJSpecify()
    writeJavaSource(
      "datadog.example.ArrayContract",
      """
      package datadog.example;
      import org.jspecify.annotations.NullMarked;
      import org.jspecify.annotations.Nullable;
      @NullMarked
      public class ArrayContract {
        public int length($parameterType values) { return values[0].length(); }
      }
      """
    )

    val result = run("compileJava", expectFailure = true)

    assertThat(result.task(":compileJava")?.outcome).describedAs(result.output).isEqualTo(FAILED)
    assertThat(result.output).contains("[NullAway]")
  }

  @Test
  fun `JSpecify permits guarded nullable values and leaves unmarked code unchecked`() {
    useJSpecify()
    writeJavaSource(
      "datadog.example.ArrayContract",
      """
      package datadog.example;
      import org.jspecify.annotations.NullMarked;
      import org.jspecify.annotations.Nullable;
      @NullMarked
      public class ArrayContract {
        public int length(@Nullable String @Nullable ... values) {
          if (values == null) return 0;
          int length = 0;
          for (String value : values) {
            if (value != null) length += value.length();
          }
          return length;
        }
      }
      """
    )
    writeViolation("main_java17")
    writeRootProject("""sourceSets.create("main_java17")""", append = true)

    val result = run("compileJava", "compileMain_java17Java")

    assertThat(result.task(":compileJava")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(result.task(":compileMain_java17Java")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
  }

  private fun useJSpecify() {
    writeRootProject(
      """
      plugins { id("dd-trace-java.conventions.jspecify") }
      repositories { mavenCentral() }
      tasks.withType<JavaCompile>().configureEach { options.release = 8 }
      """
    )
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
