package datadog.gradle.plugin.errorprone

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ErrorProneConventionsPluginTest : GradleFixture() {
  @BeforeEach
  fun setup() {
    writeSettings("""rootProject.name = "errorprone-convention"""")
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
      import net.ltgt.gradle.errorprone.errorprone

      plugins { id("dd-trace-java.conventions.errorprone") }
      repositories { mavenCentral() }
      nullAway { basePackage = "datadog.example" }
      tasks.withType<JavaCompile>().configureEach { options.release = 8 }
      """
    )
  }

  @ParameterizedTest
  @ValueSource(strings = ["", "nullAway { basePackage = \"\" }"])
  fun `checking requires a nonblank base package`(configuration: String) {
    writeRootProject(
      """
      import net.ltgt.gradle.errorprone.errorprone

      plugins { id("dd-trace-java.conventions.errorprone") }
      repositories { mavenCentral() }
      $configuration
      tasks.named<JavaCompile>("compileJava") {
        options.release = 8
        check(!options.errorprone.isEnabled.get())
        check(options.compilerArgumentProviders.flatMap { it.asArguments() }.isEmpty())
      }
      """
    )
    writeViolation("main")

    val result = run("compileJava", "--configuration-cache")

    assertThat(result.task(":compileJava")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(result.output).doesNotContain("[NullAway]", "Error Prone is disabled")
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
  fun `other Error Prone checks remain disabled by default`() {
    writeJavaSource(
      "datadog.example.Compliant",
      """
      package datadog.example;
      public class Compliant {
        public String value() {
          new IllegalStateException();
          return String.format("%d", "value");
        }
      }
      """
    )

    val result = run("compileJava", "--configuration-cache")

    assertThat(result.task(":compileJava")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(result.output).doesNotContain("[DeadException]", "[FormatString]")
    assertThat(run("compileJava", "--configuration-cache").output).contains("Reusing configuration cache")
  }

  @Test
  fun `IAST settings retain built-in Error Prone errors`() {
    writeRootProject(
      """
      tasks.named<JavaCompile>("compileJava") {
        options.errorprone {
          disableAllChecks = false
          disableAllWarnings = true
        }
      }
      """,
      append = true
    )
    writeJavaSource(
      "datadog.example.BadFormat",
      """
      package datadog.example;
      public class BadFormat {
        public String value() {
          new IllegalStateException();
          return String.format("%d", "value");
        }
      }
      """
    )

    val result = run("compileJava", expectFailure = true)

    assertThat(result.task(":compileJava")?.outcome).describedAs(result.output).isEqualTo(FAILED)
    assertThat(result.output).contains("[FormatString]", "[DeadException]")
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

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `an older production compiler requires an explicit opt-out`(disabled: Boolean) {
    writeRootProject(
      """
      import net.ltgt.gradle.errorprone.errorprone
      import org.gradle.jvm.toolchain.JavaCompiler
      import org.gradle.jvm.toolchain.JavaInstallationMetadata

      plugins { id("dd-trace-java.conventions.errorprone") }

      nullAway { basePackage = "datadog.example" }
      val currentCompiler = javaToolchains.compilerFor {}
      val compile = tasks.named<JavaCompile>("compileJava").get()
      compile.javaCompiler.set(currentCompiler.map { compiler ->
        object : JavaCompiler by compiler {
          override fun getMetadata(): JavaInstallationMetadata =
            object : JavaInstallationMetadata by compiler.metadata {
              override fun getLanguageVersion() = JavaLanguageVersion.of(11)
            }
        }
      })

      ${if (disabled) "compile.options.errorprone.isEnabled = false" else ""}
      check(compile.options.compilerArgumentProviders.flatMap { it.asArguments() }.isEmpty())
      """
    )

    val result = run("help", expectFailure = !disabled)

    assertThat(result.task(":compileJava")).isNull()
    if (disabled) {
      assertThat(result.task(":help")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    } else {
      assertThat(result.task(":help")).isNull()
      assertThat(result.output).contains(
        "Error Prone requires JDK 21 or later for :compileJava, but its compiler is JDK 11.",
        "explicitly disable Error Prone for this task"
      )
    }
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
