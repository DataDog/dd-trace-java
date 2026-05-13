package datadog.gradle.plugin.dump

import datadog.gradle.plugin.GradleFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DumpHangedTestIntegrationTest {
  @Test
  fun `should not take dumps`(@TempDir projectDir: File) {
    val output = runGradleTest(projectDir, testSleepMillis = 1000)

    // Assert Gradle output has no evidence of taking dumps.
    assertFalse(output.contains("Taking dumps after 15 seconds delay for :test"))
    assertFalse(output.contains("Requesting stop of task ':test' as it has exceeded its configured timeout of 20s."))

    assertTrue(File(projectDir, "build").exists()) // Assert build happened.
    assertFalse(File(projectDir, "build/dumps").exists()) // Assert no dumps created.
  }

  @Test
  fun `should take dumps`(@TempDir projectDir: File) {
    val output = runGradleTest(projectDir, testSleepMillis = 25_0000)

    // Assert Gradle output has evidence of taking dumps.
    assertTrue(output.contains("Taking dumps after 15 seconds delay for :test"))
    assertTrue(output.contains("Requesting stop of task ':test' as it has exceeded its configured timeout of 20s."))

    assertTrue(File(projectDir, "build").exists()) // Assert build happened.

    val dumps = File(projectDir, "build/dumps")
    assertTrue(dumps.exists()) // Assert dumps created.

    // Assert actual dumps created.
    val dumpFiles = dumps.list()
    assertNotNull(dumpFiles.find { it.endsWith(".hprof") })
    assertNotNull(dumpFiles.find { it.startsWith("all-thread-dumps") })
  }

  private fun runGradleTest(projectDir: File, testSleepMillis: Long): List<String> {
    val fixture = GradleFixture(projectDir)

    fixture.settings(
      """
      rootProject.name = 'test-project'
      """
    )

    fixture.rootProject(
      """
      import java.time.Duration

      plugins {
        id 'java'
        id 'dd-trace-java.dump-hanged-test'
      }

      group = 'datadog.dump.test'

      repositories {
        mavenCentral()
      }

      dependencies {
        testImplementation platform('org.junit:junit-bom:5.10.0')
        testImplementation 'org.junit.jupiter:junit-jupiter'
        testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
      }

      dumpHangedTest {
        // Set the dump offset for 5 seconds to trigger taking dumps after 15 seconds.
        dumpOffset.set(5)
      }

      tasks.withType(Test).configureEach {
        // Set test timeout after 20 seconds.
        timeout.set(Duration.ofSeconds(20))

        useJUnitPlatform()
      }
      """
    )

    fixture.writeTest(
      "SimpleTest",
      """
      import org.junit.jupiter.api.Test;

      public class SimpleTest {
          @Test
          public void test() throws InterruptedException {
              Thread.sleep($testSleepMillis);
          }
      }
      """
    )

    return fixture.run("test", forwardOutput = true).output.lines()
  }
}
