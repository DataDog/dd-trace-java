package datadog.gradle.plugin.dump

import datadog.gradle.plugin.GradleFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File

class DumpHangedTestIntegrationTest : GradleFixture() {
  @Test
  fun `should not take dumps`() {
    val output = runGradleTest(testSleepMillis = 1000)

    // Assert Gradle output has no evidence of taking dumps.
    assertFalse(output.contains("Taking dumps after 15 seconds delay for :test"))
    assertFalse(output.contains("Requesting stop of task ':test' as it has exceeded its configured timeout of 20s."))

    assertTrue(buildDir.exists()) // Assert build happened.
    assertFalse(buildFile("dumps").exists()) // Assert no dumps created.
  }

  @Test
  fun `should take dumps`() {
    val output = runGradleTest(testSleepMillis = 25_0000)

    // Assert Gradle output has evidence of taking dumps.
    assertTrue(output.contains("Taking dumps after 15 seconds delay for :test"))
    assertTrue(output.contains("Requesting stop of task ':test' as it has exceeded its configured timeout of 20s."))
    assertTrue(buildDir.exists()) // Assert build happened.

    val dumps = buildFile("dumps")
    assertTrue(dumps.exists()) // Assert dumps created.
    assertTrue(output.any { it.startsWith("Starting dump command for :test:") && it.contains("GC.heap_dump") })
    assertTrue(output.any { it.startsWith("Completed dump command for :test") && it.contains("Thread.print -l") })

    // Assert actual dumps created.
    val dumpFiles = dumps.list()
    assertNotNull(dumpFiles.find { it.endsWith(".hprof") })
    assertNotNull(dumpFiles.find { it.startsWith("all-thread-dumps") })
  }

  @Test
  fun `should start dumps three minutes before timeout by default`() {
    val output = runGradleTest(testSleepMillis = 10_000, timeoutSeconds = 185, dumpOffset = null)

    assertTrue(output.contains("Taking dumps after 5 seconds delay for :test"))
    assertFalse(output.any { it.contains("has exceeded its configured timeout") })
  }

  @Test
  fun `should report directory failures with stack trace`() {
    writeFile("build/dumps", "This file prevents creating the dump directory")

    val output = runGradleTest(testSleepMillis = 25_000)

    assertTrue(output.any { it.contains("Taking dumps failed for :test") })
    assertTrue(output.any { it.contains("java.io.IOException: Could not create dump directory") })
    assertTrue(output.any { it.contains("DumpHangedTestPlugin.takeDump(") })
  }

  @Test
  @EnabledOnOs(OS.LINUX, OS.MAC)
  fun `should collect all thread dumps before attempting heap dumps`() {
    val jcmd = writeFile(
      "bin/jcmd",
      """
      #!/bin/sh
      if [ "${'$'}2" = "GC.heap_dump" ]; then
        echo "Synthetic heap dump failure" >&2
        exit 7
      fi
      echo "Synthetic thread dump for PID ${'$'}1"
      """
    )
    assertTrue(jcmd.setExecutable(true))
    // Start a separate daemon so subprocess lookup uses this test's PATH.
    writeGradleProperties("org.gradle.jvmargs=-DdumpFailureTest=true")

    val output = runGradleTest(
      testSleepMillis = 25_000,
      env = mapOf("PATH" to "${jcmd.parent}${File.pathSeparator}${System.getenv("PATH")}")
    )

    assertTrue(output.any { it.startsWith("Dump command failed for :test:") && it.contains("GC.heap_dump") })
    assertTrue(output.any { it.contains("java.io.IOException: Process failed with exit code 7") })
    assertTrue(output.any { it.contains("DumpHangedTestPlugin.runCmd(") })
    val dumps = buildFile("dumps").listFiles().orEmpty()
    assertTrue(dumps.any { it.name.contains("-thread-dump-") && it.readText().startsWith("Synthetic thread dump") })
    assertTrue(dumps.any { it.name.startsWith("all-thread-dumps-") && it.readText().contains("PID 0") })
    assertTrue(output.any { it.startsWith("Finished dump collection for :test;") })
    val firstHeapDump = output.indexOfFirst { it.startsWith("Starting dump command") && it.contains("GC.heap_dump") }
    val lastThreadDump = output.indexOfLast { it.startsWith("Completed dump command") && it.contains("Thread.print") }
    assertTrue(lastThreadDump >= 0 && firstHeapDump > lastThreadDump)
  }

  private fun runGradleTest(
    testSleepMillis: Long,
    env: Map<String, String> = emptyMap(),
    timeoutSeconds: Long = 20,
    dumpOffset: Long? = 5
  ): List<String> {
    writeSettings("""rootProject.name = "test-project"""")

    writeRootProject(
      """
      import java.time.Duration
      import org.gradle.api.tasks.testing.Test

      plugins {
        id("java")
        id("dd-trace-java.dump-hanged-test")
      }

      group = "datadog.dump.test"

      repositories {
        mavenCentral()
      }

      dependencies {
        testImplementation(platform("org.junit:junit-bom:5.10.0"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
      }

      dumpHangedTest {
        ${dumpOffset?.let { "dumpOffset.set($it)" } ?: ""}
      }

      tasks.withType<Test>().configureEach {
        timeout.set(Duration.ofSeconds($timeoutSeconds))

        useJUnitPlatform()
      }
      """
    )

    writeJavaSource(
      "SimpleTest",
      """
      import org.junit.jupiter.api.Test;

      public class SimpleTest {
          @Test
          public void test() throws InterruptedException {
              Thread.sleep($testSleepMillis);
          }
      }
      """,
      sourceSet = "test"
    )

    return run("test", env = env, forwardOutput = true).output.lines()
  }
}
