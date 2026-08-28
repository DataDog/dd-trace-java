package datadog.gradle.plugin.jardiff

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JardiffComparisonTest {

  private val reference = File("/tmp/reference/dd-java-agent-1.0.0.jar")
  private val candidate = File("/tmp/candidate/dd-java-agent-1.0.0.jar")

  @Test
  fun `builds stat comparison arguments with the reference on the left`() {
    val arguments = JardiffComparison.buildArguments(reference, candidate, mode = "--stat")

    assertThat(arguments).containsExactly(
      "--stat",
      "--exit-code",
      "--color=never",
      reference.absolutePath,
      candidate.absolutePath,
    )
  }

  @Test
  fun `joins include patterns with commas before the positional arguments`() {
    val arguments = JardiffComparison.buildArguments(
      reference,
      candidate,
      mode = "--stat",
      includes = listOf("**/*.class", "META-INF/**"),
    )

    assertThat(arguments).containsExactly(
      "--stat",
      "--exit-code",
      "--color=never",
      "--include=**/*.class,META-INF/**",
      reference.absolutePath,
      candidate.absolutePath,
    )
  }

  @Test
  fun `joins exclude patterns with commas`() {
    val arguments = JardiffComparison.buildArguments(
      reference,
      candidate,
      mode = "--stat",
      excludes = listOf("**/*.txt"),
    )

    assertThat(arguments).contains("--exclude=**/*.txt")
    // Both filter flags precede the two jars.
    assertThat(arguments.indexOf("--exclude=**/*.txt"))
      .isLessThan(arguments.indexOf(reference.absolutePath))
  }

  @Test
  fun `omits filter flags when no patterns are given`() {
    val arguments = JardiffComparison.buildArguments(reference, candidate, mode = "--stat")

    assertThat(arguments).noneMatch { it.startsWith("--include") || it.startsWith("--exclude") }
  }

  @Test
  fun `uses the given mode, and omits it when blank`() {
    assertThat(JardiffComparison.buildArguments(reference, candidate, mode = "--status"))
      .startsWith("--status")
    assertThat(JardiffComparison.buildArguments(reference, candidate, mode = ""))
      .startsWith("--exit-code")
  }

  @Test
  fun `appends additional options before the positional arguments`() {
    val arguments = JardiffComparison.buildArguments(
      reference,
      candidate,
      mode = "--stat",
      additionalOptions = listOf("--ignore-member-order", "--class-text-producer=javap"),
    )

    assertThat(arguments).contains("--ignore-member-order", "--class-text-producer=javap")
    assertThat(arguments.indexOf("--ignore-member-order"))
      .isLessThan(arguments.indexOf(reference.absolutePath))
  }

  @Test
  fun `maps exit values to outcomes like diff`() {
    assertThat(JardiffComparison.outcomeOf(0)).isEqualTo(JardiffComparison.Outcome.IDENTICAL)
    assertThat(JardiffComparison.outcomeOf(1)).isEqualTo(JardiffComparison.Outcome.DIFFERENT)
    assertThat(JardiffComparison.outcomeOf(2)).isEqualTo(JardiffComparison.Outcome.ERROR)
    assertThat(JardiffComparison.outcomeOf(137)).isEqualTo(JardiffComparison.Outcome.ERROR)
  }
}
