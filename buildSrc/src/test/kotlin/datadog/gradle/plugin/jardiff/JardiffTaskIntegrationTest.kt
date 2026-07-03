package datadog.gradle.plugin.jardiff

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

/**
 * Exercises the `dd-trace-java.jardiff` plugin and its `compareToReferenceJar` task end-to-end.
 */
class JardiffTaskIntegrationTest : GradleFixture() {

  @Test
  fun `passes when the candidate matches the reference`() {
    writeProject()
    writeJar("candidate.jar", "same-bytes")
    writeJar("reference.jar", "same-bytes")

    val result = run("compareToReferenceJar")

    assertThat(result.task(":compareToReferenceJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(buildFile("reports/jardiff/comparison.txt")).exists().content()
      .contains("no differences")
  }

  @Test
  fun `fails and reports the diff when the candidate differs`() {
    writeProject()
    writeJar("candidate.jar", "candidate-bytes")
    writeJar("reference.jar", "reference-bytes")

    val result = run("compareToReferenceJar", expectFailure = true)

    assertThat(result.task(":compareToReferenceJar")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains("Built jar differs from the reference jar")
      .contains("1 file changed")
    assertThat(buildFile("reports/jardiff/comparison.txt")).exists().content()
      .contains("1 file changed")
  }

  @Test
  fun `reference-jar option takes precedence over the referenceJar property`() {
    writeProject()
    writeJar("candidate.jar", "shared-bytes")
    // The wired reference would match (identical bytes) and pass...
    writeJar("reference.jar", "shared-bytes")
    // ...but the command-line override points at a diverging jar, so the build must fail.
    writeJar("override.jar", "diverging-bytes")

    val result = run(
      "compareToReferenceJar",
      "--reference-jar=${file("override.jar").absolutePath}",
      expectFailure = true,
    )

    assertThat(result.task(":compareToReferenceJar")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output).contains("Built jar differs from the reference jar")
  }

  @Test
  fun `resolves the reference from jardiffReferenceDir by matching the candidate file name`() {
    writeProject(referenceJarLine = "")
    writeJar("candidate.jar", "dir-bytes")
    // A directory holding a jar with the SAME file name as the candidate (as across CI jobs).
    dir("refs")
    writeJar("refs/candidate.jar", "dir-bytes")

    val result = run("compareToReferenceJar", "-PjardiffReferenceDir=${file("refs").absolutePath}")

    assertThat(result.task(":compareToReferenceJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(buildFile("reports/jardiff/comparison.txt")).exists().content()
      .contains("no differences")
  }

  @Test
  fun `fails when no reference is configured`() {
    // The comparison is mandatory: a missing reference must fail, never silently skip.
    writeProject(referenceJarLine = "")
    writeJar("candidate.jar", "candidate-bytes")

    val result = run("compareToReferenceJar", expectFailure = true)

    assertThat(result.task(":compareToReferenceJar")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output).contains("No reference jar configured")
    assertThat(buildFile("reports/jardiff/comparison.txt")).doesNotExist()
  }

  @Test
  fun `passes include and exclude patterns to jardiff`() {
    writeProject(
      taskBody = """
        includes.set(listOf("**/*.class", "META-INF/**"))
        excludes.set(listOf("**/*.txt"))
      """,
    )
    writeJar("candidate.jar", "same-bytes")
    writeJar("reference.jar", "same-bytes")

    val result = run("compareToReferenceJar")

    assertThat(result.task(":compareToReferenceJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(buildFile("reports/jardiff/comparison.txt")).content()
      .contains("--include=**/*.class,META-INF/**")
      .contains("--exclude=**/*.txt")
  }

  @Test
  fun `applies main class, mode and additional options from the jardiff extension`() {
    writeSettings("""rootProject.name = "jardiff-ext-test"""")
    writeJavaSource("fake.jardiff.Main", stubMainSource("fake.jardiff"))
    writeRootProject(
      """
      import datadog.gradle.plugin.jardiff.JardiffTask

      plugins {
        java
        id("dd-trace-java.jardiff")
      }

      jardiff {
        mainClass.set("fake.jardiff.Main")
        mode.set("--status")
        additionalOptions.set(listOf("--ignore-member-order"))
      }

      tasks.named<JardiffTask>("compareToReferenceJar") {
        dependsOn("classes")
        jardiffClasspath.setFrom(sourceSets["main"].output)
        candidateJar.set(layout.projectDirectory.file("candidate.jar"))
        referenceJar.set(layout.projectDirectory.file("reference.jar"))
      }
      """,
    )
    writeJar("candidate.jar", "same-bytes")
    writeJar("reference.jar", "same-bytes")

    val result = run("compareToReferenceJar")

    // SUCCESS proves the extension's mainClass reached the task (the stub Main is 'fake.jardiff.Main',
    // not the default), and the report echoes the mode + additional option from the extension.
    assertThat(result.task(":compareToReferenceJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(buildFile("reports/jardiff/comparison.txt")).content()
      .contains("--status")
      .contains("--ignore-member-order")
  }

  @Test
  fun `applies mode and additional options passed as command-line options`() {
    writeProject()
    writeJar("candidate.jar", "same-bytes")
    writeJar("reference.jar", "same-bytes")

    val result = run(
      "compareToReferenceJar",
      "--mode=--status",
      "--jardiff-option=--ignore-member-order",
      "--jardiff-option=--class-text-producer=javap",
    )

    assertThat(result.task(":compareToReferenceJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(buildFile("reports/jardiff/comparison.txt")).content()
      .contains("--status")
      .contains("--ignore-member-order")
      .contains("--class-text-producer=javap")
  }

  @Test
  fun `ignoreVersionFiles excludes version stamps from the comparison`() {
    writeProject(taskBody = "ignoreVersionFiles.set(true)")
    writeJar("candidate.jar", "same-bytes")
    writeJar("reference.jar", "same-bytes")

    val result = run("compareToReferenceJar")

    assertThat(result.task(":compareToReferenceJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(buildFile("reports/jardiff/comparison.txt")).content()
      .contains("--exclude=**/*.version")
  }

  @Test
  fun `compares version stamps under CI (ignoreVersionFiles defaults to false)`() {
    // No explicit ignoreVersionFiles: the plugin's CI-aware convention drives it. With CI set, the
    // build and deploy jobs share a commit, so the stamps are compared (not excluded).
    writeProject()
    writeJar("candidate.jar", "same-bytes")
    writeJar("reference.jar", "same-bytes")

    val result = run("compareToReferenceJar", env = mapOf("CI" to "true"))

    assertThat(result.task(":compareToReferenceJar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(buildFile("reports/jardiff/comparison.txt")).content()
      .doesNotContain("**/*.version")
  }

  private fun writeJar(name: String, content: String) {
    file(name).also { it.parentFile?.mkdirs() }.writeBytes(content.toByteArray())
  }

  private fun writeProject(
    taskBody: String = "",
    referenceJarLine: String =
      """referenceJar.set(layout.projectDirectory.file("reference.jar"))""",
  ) {
    writeSettings("""rootProject.name = "jardiff-stub-test"""")
    writeJavaSource("fake.jardiff.Main", stubMainSource("fake.jardiff"))
    // The task's classpath and main class point at the compiled stub instead of the real jardiff CLI.
    writeRootProject(
      """
      import datadog.gradle.plugin.jardiff.JardiffTask

      plugins {
        java
        id("dd-trace-java.jardiff")
      }

      tasks.named<JardiffTask>("compareToReferenceJar") {
        dependsOn("classes")
        jardiffClasspath.setFrom(sourceSets["main"].output)
        mainClass.set("fake.jardiff.Main")
        candidateJar.set(layout.projectDirectory.file("candidate.jar"))
        $referenceJarLine
        $taskBody
      }
      """,
    )
  }

  companion object {
    /**
     * Minimal stand-in for the jardiff CLI in the given package: prints the received arguments,
     * byte-compares the last two positional arguments (left/right jars) and mirrors jardiff's
     * `--exit-code` semantics.
     */
    private fun stubMainSource(packageName: String): String = """
      package $packageName;

      import java.nio.file.Files;
      import java.nio.file.Paths;
      import java.util.Arrays;

      public class Main {
        public static void main(String[] args) throws Exception {
          System.out.println("jardiff-stub args: " + Arrays.toString(args));
          String left = args[args.length - 2];
          String right = args[args.length - 1];
          byte[] leftBytes = Files.readAllBytes(Paths.get(left));
          byte[] rightBytes = Files.readAllBytes(Paths.get(right));
          if (Arrays.equals(leftBytes, rightBytes)) {
            System.out.println("no differences");
            System.exit(0);
          }
          System.out.println(" 1 file changed, 1 insertion(+)");
          System.exit(Arrays.asList(args).contains("--exit-code") ? 1 : 0);
        }
      }
    """.trimIndent()
  }
}
