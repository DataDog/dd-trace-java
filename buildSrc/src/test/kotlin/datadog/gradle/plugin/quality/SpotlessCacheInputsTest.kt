package datadog.gradle.plugin.quality

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class SpotlessCacheInputsTest : GradleFixture() {
  private val formats = listOf("GroovyGradle", "KotlinGradle")

  @BeforeEach
  fun setup() {
    writeSettings(
      """
      rootProject.name = "dd-trace-java"
      include(":dd-java-agent", ":other")
      dependencyResolutionManagement { repositories { mavenCentral() } }
      buildCache { local { directory = file("${projectDir.resolve("task-cache").invariantSeparatorsPath}") } }
      """
    )
    listOf("spotless.gradle", "libs.versions.toml", "enforcement/spotless-groovy.properties").forEach {
      writeFile("gradle/$it", File("../gradle/$it").readText())
    }
    writeFile(
      "build.gradle",
      """
      plugins { alias(libs.plugins.spotless) apply false }
      allprojects {
        apply plugin: 'base'
        apply from: rootProject.file('gradle/spotless.gradle')
        spotless {
          groovyGradle { target 'candidate.gradle' }
          kotlinGradle { target 'candidate.gradle.kts' }
        }
      }
      """
    )
    listOf("", "dd-java-agent/", "other/").forEach { prefix ->
      writeFile("${prefix}candidate.gradle", "// Allowed script")
      writeFile("${prefix}candidate.gradle.kts", "// Allowed script")
    }
  }

  @Test
  fun `restores both formats in a relocated checkout and fresh daemon`() {
    assertFormats(runChecks(), SUCCESS)
    assertFormats(runChecks(), UP_TO_DATE)

    val relocated = file("relocated")
    listOf("settings.gradle.kts", "build.gradle", "gradle", "candidate.gradle", "candidate.gradle.kts", "dd-java-agent", "other").forEach {
      file(it).copyRecursively(relocated.resolve(it))
    }
    val restored = run(
      "spotlessGroovyGradleCheck",
      "spotlessKotlinGradleCheck",
      "--build-cache",
      "-Dorg.gradle.jvmargs=-Xmx256m -Dspotless.test.freshDaemon=true",
      gradleProjectDir = relocated,
    )
    assertFormats(restored, FROM_CACHE)

    writeFile("relocated/candidate.gradle", "// Changed script")
    writeFile("relocated/candidate.gradle.kts", "// Changed script")
    val changed = run(
      "spotlessGroovyGradleCheck",
      "spotlessKotlinGradleCheck",
      "--build-cache",
      gradleProjectDir = relocated,
    )
    assertFormats(changed, SUCCESS)
  }

  @ParameterizedTest
  @ValueSource(strings = ["GroovyGradle", "KotlinGradle"])
  fun `rejects forbidden applies while allowing comments and unrelated lines`(format: String) {
    val script = if (format == "GroovyGradle") "candidate.gradle" else "candidate.gradle.kts"
    val forbidden = if (format == "GroovyGradle") {
      "project.apply from: \"gradle/java.gradle\""
    } else {
      "apply(from = \"gradle/java.gradle\")"
    }
    writeFile(script, "// $forbidden\n// apply\n// from: gradle/java.gradle")
    val allowed = run("spotless${format}Check", "--build-cache")
    assertThat(allowed.task(":spotless${format}Check")?.outcome).describedAs(allowed.output).isEqualTo(SUCCESS)

    writeFile(script, forbidden)
    val rejected = run("spotless${format}Check", "--build-cache", expectFailure = true)
    assertThat(rejected.task(":spotless${format}Check")?.outcome).describedAs(rejected.output).isEqualTo(FAILED)
    assertThat(rejected.output).contains("convention plugins instead of applying the script plugin 'gradle/java.gradle'")
  }

  @Test
  fun `rules and messages invalidate both formats`() {
    assertFormats(runChecks(), SUCCESS)
    val rules = file("gradle/spotless.gradle")
    rules.writeText(rules.readText().replace("gradle/java.gradle", "gradle/obsolete.gradle"))
    assertFormats(runChecks(), SUCCESS)
    rules.writeText(rules.readText().replace("convention plugins instead", "replacement convention plugins instead"))
    assertFormats(runChecks(), SUCCESS)
  }

  @Test
  fun `publish exception is project specific and invalidates when removed`() {
    val candidate = "apply from: \"gradle/publish.gradle\""
    writeFile("dd-java-agent/candidate.gradle", candidate)
    writeFile("other/candidate.gradle", candidate)
    val allowed = run(":dd-java-agent:spotlessGroovyGradleCheck", "--build-cache")
    assertThat(allowed.task(":dd-java-agent:spotlessGroovyGradleCheck")?.outcome).describedAs(allowed.output).isEqualTo(SUCCESS)

    val rejected = run(":other:spotlessGroovyGradleCheck", "--build-cache", expectFailure = true)
    assertThat(rejected.task(":other:spotlessGroovyGradleCheck")?.outcome).describedAs(rejected.output).isEqualTo(FAILED)
    assertThat(rejected.output).contains("gradle/publish.gradle")

    val rules = file("gradle/spotless.gradle")
    rules.writeText(rules.readText().replace("[':dd-java-agent'] as Set", "[] as Set"))
    val removed = run(":dd-java-agent:spotlessGroovyGradleCheck", "--build-cache", expectFailure = true)
    assertThat(removed.task(":dd-java-agent:spotlessGroovyGradleCheck")?.outcome).describedAs(removed.output).isEqualTo(FAILED)
    assertThat(removed.output).contains("gradle/publish.gradle")
  }

  @Test
  fun `standalone builds do not enforce repository script rules`() {
    val settings = file("settings.gradle.kts")
    settings.writeText(settings.readText().replace("dd-trace-java", "standalone"))
    writeFile("candidate.gradle", "apply from: \"gradle/java.gradle\"")
    writeFile("candidate.gradle.kts", """apply(from = "gradle/java.gradle")""")
    assertFormats(runChecks(), SUCCESS)
  }

  private fun runChecks(): BuildResult = run("spotlessGroovyGradleCheck", "spotlessKotlinGradleCheck", "--build-cache")

  private fun assertFormats(result: BuildResult, outcome: TaskOutcome) {
    formats.forEach {
      assertThat(result.task(":spotless$it")?.outcome).describedAs(result.output).isEqualTo(outcome)
      assertThat(result.task(":spotless${it}Check")?.outcome).describedAs(result.output).isIn(SUCCESS, UP_TO_DATE)
    }
  }
}
