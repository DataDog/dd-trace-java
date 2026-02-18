package datadog.gradle.plugin.muzzle

import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class MuzzlePluginPerformanceTest {

  @Test
  fun `task graph does not include muzzle tasks when not requested`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        pass {
          group = 'com.example.test'
          module = 'some-lib'
          versions = '[1.0.0,2.0.0)'
        }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:tasks",
      "--all",
      "--info"
    )

    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:tasks")?.outcome)

    assertFalse(
      result.tasks.any() { it.path.contains("muzzle") },
      "Should not create or execute any muzzle tasks when not requested"
    )
    assertTrue(
      result.output.contains("No muzzle tasks invoked for :dd-java-agent:instrumentation:demo, skipping muzzle task planification"),
      "Should log early return when muzzle not requested"
    )
  }

  @Test
  fun `does not configure muzzle when other project muzzle task is requested`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }
      muzzle {
        pass { coreJdk() }
      }
      """
    )
    fixture.writeNoopScanPlugin()
    fixture.addSubproject("dd-java-agent:instrumentation:other",
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }
      muzzle {
        pass { coreJdk() }
      }
      """
    )

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace",
      "--info"
    )

    assertTrue(
      result.tasks.any { it.path.contains("demo") && it.path.contains("muzzle") },
      "Should execute muzzle tasks for demo project"
    )
    assertTrue(
      result.tasks.none() { it.path.contains("other") && it.path.contains("muzzle") },
      "Should NOT create or register execute muzzle tasks for other project"
    )
    assertTrue(
      result.output.lines().any { line ->
        line.contains("No muzzle tasks invoked for :dd-java-agent:instrumentation:other, skipping muzzle task planification")
      },
      "Other project should skip muzzle configuration when demo project's muzzle is requested"
    )
  }
}
