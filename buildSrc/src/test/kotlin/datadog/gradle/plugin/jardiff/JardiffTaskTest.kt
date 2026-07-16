package datadog.gradle.plugin.jardiff

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

/** Exercises [JardiffTask] behavior without the plugin's archive/reference-dir wiring. */
class JardiffTaskTest : GradleFixture() {

  @Test
  fun `passes without running jardiff when the candidate hash matches the reference hash`() {
    writeProject()
    writeJar("candidate.jar", "same-bytes")
    writeJar("reference.jar", "same-bytes")

    val result = run("jardiffTask")

    assertThat(result.task(":jardiffTask")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(jardiffReport()).exists().content()
      .contains("SHA-256 hashes match for candidate.jar and reference.jar")
  }

  @Test
  fun `fails and reports the diff when jardiff finds differences`() {
    writeProject()
    writeJar("candidate.jar", "candidate-bytes")
    writeJar("reference.jar", "reference-bytes")

    val result = run("jardiffTask", expectFailure = true)

    assertThat(result.task(":jardiffTask")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains("Candidate jar differs from the reference jar")
      .contains("1 file changed")
      .doesNotContain("SHA-256 hashes differ")
    assertThat(jardiffReport()).exists().content()
      .contains("1 file changed")
  }

  @Test
  fun `fails when no reference is configured`() {
    writeProject(referenceJarLine = "")
    writeJar("candidate.jar", "candidate-bytes")

    val result = run("jardiffTask", expectFailure = true)

    assertThat(result.task(":jardiffTask")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output).contains("No reference jar configured")
    assertThat(jardiffReport()).doesNotExist()
  }

  @Test
  fun `fails when hashes differ but jardiff reports no differences and hashCheck is enabled`() {
    writeProject(compareBytes = false)
    writeJar("candidate.jar", "candidate-bytes")
    writeJar("reference.jar", "reference-bytes")

    val result = run("jardiffTask", expectFailure = true)

    assertThat(result.task(":jardiffTask")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains("SHA-256 hashes differ for candidate.jar and reference.jar")
      .contains("but jardiff detected no differences")
    assertThat(jardiffReport()).exists().content()
      .contains("no differences")
  }

  @Test
  fun `warns when hashes differ but jardiff reports no differences and hashCheck is disabled`() {
    writeProject(
      taskBody = "hashCheck.set(false)",
      compareBytes = false,
    )
    writeJar("candidate.jar", "candidate-bytes")
    writeJar("reference.jar", "reference-bytes")

    val result = run("jardiffTask")

    assertThat(result.task(":jardiffTask")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output)
      .contains("SHA-256 hashes differ for candidate.jar and reference.jar")
      .contains("but jardiff detected no differences")
    assertThat(jardiffReport()).exists().content()
      .contains("no differences")
  }

  private fun writeJar(name: String, content: String) {
    file(name).also { it.parentFile?.mkdirs() }.writeBytes(content.toByteArray())
  }

  private fun jardiffReport(candidateName: String = "candidate.jar") =
    buildFile("reports/jardiff/$candidateName.txt")

  private fun writeProject(
    taskBody: String = "",
    referenceJarLine: String =
      """referenceJar.set(layout.projectDirectory.file("reference.jar"))""",
    compareBytes: Boolean = true,
  ) {
    writeSettings("""rootProject.name = "jardiff-task-test"""")
    writeJavaSource("fake.jardiff.Main", stubMainSource("fake.jardiff", compareBytes))
    writeRootProject(
      """
      import datadog.gradle.plugin.jardiff.JardiffTask

      plugins {
        java
        id("dd-trace-java.jardiff")
      }

      tasks.register<JardiffTask>("jardiffTask") {
        dependsOn("classes")
        jardiffClasspath.setFrom(sourceSets["main"].output)
        mainClass.set("fake.jardiff.Main")
        mode.set("--stat")
        ignoreVersionFiles.set(true)
        candidateJar.set(layout.projectDirectory.file("candidate.jar"))
        $referenceJarLine
        $taskBody
      }
      """,
    )
  }

  companion object {
    private fun stubMainSource(packageName: String, compareBytes: Boolean = true): String = """
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
          if (${if (compareBytes) "Arrays.equals(leftBytes, rightBytes)" else "true"}) {
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
