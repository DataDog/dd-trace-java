package datadog.gradle.plugin.dump

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Paths

class DumpHangedTestIntegrationTest {
  @Test
  fun `should not take dumps`(@TempDir projectDir: File) {
    val output = runGradleTest(projectDir, testSleep = 1000)

    // Assert Gradle output has no evidence of taking dumps.
    assertFalse(output.contains("Taking dumps after 15 seconds delay for :test"))
    assertFalse(output.contains("Requesting stop of task ':test' as it has exceeded its configured timeout of 20s."))

    assertTrue(file(projectDir, "build").exists()) // Assert build happened.
    assertFalse(file(projectDir, "build", "dumps").exists()) // Assert no dumps created.
  }

  @Test
  fun `should take dumps`(@TempDir projectDir: File) {
    val output = runGradleTest(projectDir, testSleep = 25_0000)

    // Assert Gradle output has evidence of taking dumps.
    assertTrue(output.contains("Taking dumps after 15 seconds delay for :test"))
    assertTrue(output.contains("Requesting stop of task ':test' as it has exceeded its configured timeout of 20s."))

    assertTrue(file(projectDir, "build").exists()) // Assert build happened.

    val dumps = file(projectDir, "build", "dumps")
    assertTrue(dumps.exists()) // Assert dumps created.

    // Assert actual dumps created.
    val dumpFiles = dumps.list()
    assertNotNull(dumpFiles.find { it.endsWith(".hprof") })
    assertNotNull(dumpFiles.find { it.startsWith("all-thread-dumps") })
  }

  private fun runGradleTest(projectDir: File, testSleep: Long): List<String> {
    file(projectDir, "settings.gradle.kts").writeText(
      """
      rootProject.name = "test-project"
      """.trimIndent()
    )

    file(projectDir, "build.gradle.kts").writeText(
      """
      import java.time.Duration
      
      plugins {
        id("java")
        id("datadog.dump-hanged-test")
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
        // Set the dump offset for 5 seconds to trigger taking dumps after 15 seconds.
        dumpOffset.set(5)
      }
      
      tasks.withType<Test>().configureEach {
        // Set test timeout after 20 seconds.
        timeout.set(Duration.ofSeconds(20))
        
        useJUnitPlatform()
      }
      """.trimIndent()
    )

    file(projectDir, "src", "test", "java", "SimpleTest.java", makeDirectory = true).writeText(
      """
      import org.junit.jupiter.api.Test;
      
      public class SimpleTest {
          @Test
          public void test() throws InterruptedException {
              Thread.sleep($testSleep);
          }
      }
      """.trimIndent()
    )

    try {
      val buildResult = GradleRunner.create()
        .forwardOutput()
        .withPluginClasspath()
        .withArguments("test")
        .withProjectDir(projectDir)
        .build()

      return buildResult.output.lines()
    } catch (e: UnexpectedBuildFailure) {
      return e.buildResult.output.lines()
    }
  }

  private fun file(projectDir: File, vararg parts: String, makeDirectory: Boolean = false): File {
    val f = Paths.get(projectDir.absolutePath, *parts).toFile()

    if (makeDirectory) {
      f.parentFile.mkdirs()
    }

    return f
  }
}
