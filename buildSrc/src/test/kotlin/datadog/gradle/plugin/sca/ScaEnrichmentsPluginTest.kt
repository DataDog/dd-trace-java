package datadog.gradle.plugin.sca

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ScaEnrichmentsPluginTest : GradleFixture() {

  @BeforeEach
  fun setup() {
    writeSettings("""rootProject.name = "test-appsec"""")
    writeRootProject(
      """
      plugins {
        java
        id("dd-trace-java.sca-enrichments")
      }
      """
    )
  }

  @Test
  fun `generateScaCvesJson is SKIPPED when file exists and refreshSca is not set`() {
    file("src/main/resources/sca_cves.json").also {
      it.parentFile.mkdirs()
      it.writeText("{\"version\":1,\"entries\":[]}")
    }

    val result = run("generateScaCvesJson")

    assertThat(result.task(":generateScaCvesJson")?.outcome).isEqualTo(TaskOutcome.SKIPPED)
  }

  @Test
  fun `generateScaCvesJson attempts to run when refreshSca is set even if file exists`() {
    file("src/main/resources/sca_cves.json").also {
      it.parentFile.mkdirs()
      it.writeText("{}")
    }

    // With -PrefreshSca the onlyIf condition is true; task will fail at the GitHub fetch
    // (no network in tests) but must NOT be SKIPPED
    val result = run("generateScaCvesJson", "-PrefreshSca", expectFailure = true)

    assertThat(result.task(":generateScaCvesJson")?.outcome)
        .isNotNull
        .isNotEqualTo(TaskOutcome.SKIPPED)
  }

  @Test
  fun `generateScaCvesJson attempts to run when output file does not exist`() {
    // File absent: onlyIf returns true; task will fail at GitHub fetch but must not be SKIPPED
    val result = run("generateScaCvesJson", expectFailure = true)

    assertThat(result.task(":generateScaCvesJson")?.outcome)
        .isNotNull
        .isNotEqualTo(TaskOutcome.SKIPPED)
  }

  @Test
  fun `processResources depends on generateScaCvesJson`() {
    file("src/main/resources/sca_cves.json").also {
      it.parentFile.mkdirs()
      it.writeText("{\"version\":1,\"entries\":[]}")
    }

    val result = run("processResources")

    // generateScaCvesJson must appear as SKIPPED (file exists, no -PrefreshSca)
    assertThat(result.task(":generateScaCvesJson")?.outcome).isEqualTo(TaskOutcome.SKIPPED)
    assertThat(result.task(":processResources")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }
}
