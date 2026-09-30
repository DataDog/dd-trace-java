package datadog.gradle.plugin.tags

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TagRegistryGeneratorPluginTest : GradleFixture() {
  @BeforeEach
  fun setUp() {
    writeFile("settings.gradle.kts", """rootProject.name = "tags-test"""")
    writeRootProject(
      """
      plugins {
        `java-library`
        id("dd-trace-java.tag-registry-generator")
      }

      tagRegistry {
        domainYaml.set(layout.projectDirectory.file("tag-conventions.yaml"))
        destinationDirectory.set(layout.projectDirectory.dir("src/generated"))
      }

      sourceSets["main"].java.srcDir("src/generated/java")
      """)
    writeConventions(otelName = "http.request.method")
  }

  private fun writeConventions(otelName: String) {
    writeFile(
      "tag-conventions.yaml",
      """
      span_types:
        web:
          tags:
            - { dd-name: http.method, type: string, required: required, otel-name: $otelName }
      """)
  }

  @Test
  fun `verifyKnownTags passes on freshly generated output`() {
    run("generateKnownTags")

    val result = run("verifyKnownTags")

    assertThat(result.task(":verifyKnownTags")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `verifyKnownTags rejects output that is stale against the conventions`() {
    run("generateKnownTags")
    writeConventions(otelName = "http.request.verb")

    val result = run("verifyKnownTags", expectFailure = true)

    assertThat(result.task(":verifyKnownTags")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains("out of date: java/datadog/trace/api/KnownTags.java")
      .contains("generateKnownTags")
  }

  @Test
  fun `verifyKnownTags rejects a committed file that is no longer generated`() {
    run("generateKnownTags")
    writeFile("src/generated/retired.txt", "left over")

    val result = run("verifyKnownTags", expectFailure = true)

    assertThat(result.output).contains("stale (no longer generated): retired.txt")
  }

  @Test
  fun `compileJava fails against stale generated sources`() {
    run("generateKnownTags")
    writeConventions(otelName = "http.request.verb")

    val result = run("compileJava", expectFailure = true)

    assertThat(result.task(":verifyKnownTags")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.task(":compileJava")).isNull()
  }

  @Test
  fun `generate then verify in one invocation runs in order`() {
    writeConventions(otelName = "http.request.verb")

    val result = run("verifyKnownTags", "generateKnownTags")

    assertThat(result.task(":generateKnownTags")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":verifyKnownTags")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }
}
