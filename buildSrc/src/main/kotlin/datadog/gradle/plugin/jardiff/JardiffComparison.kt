package datadog.gradle.plugin.jardiff

import java.io.File

/**
 * Pure, Gradle-free helpers for driving the [jardiff](https://github.com/bric3/jardiff) CLI.
 *
 * Kept separate from [JardiffTask] so the argument construction and exit-code interpretation can
 * be unit-tested without spinning up a Gradle build or resolving the jardiff jar.
 */
object JardiffComparison {
  /** Outcome of a jardiff run, derived from its process exit value. */
  enum class Outcome {
    /** Exit 0 — the two jars are identical (for the selected include/exclude set). */
    IDENTICAL,

    /** Exit 1 — jardiff reported differences (behaves like `diff(1)` under `--exit-code`). */
    DIFFERENT,

    /** Any other exit value — jardiff itself failed (bad arguments, unreadable jar, ...). */
    ERROR,
  }

  /**
   * Builds the jardiff argument list comparing [reference] (left) against [candidate] (right).
   *
   * [mode] selects the output mode (e.g. `--stat` for a `git diff --stat`-like summary, `--status`,
   * or blank for the default full diff). `--exit-code` is always added.
   * The [JardiffTask] relies on the process exit value.
   * [includes]/[excludes] are comma-joined glob patterns (empty means comparing every entry), and
   * [additionalOptions] are passed through verbatim, right before the two jars.
   */
  fun buildArguments(
    reference: File,
    candidate: File,
    mode: String,
    includes: List<String> = emptyList(),
    excludes: List<String> = emptyList(),
    additionalOptions: List<String> = emptyList(),
  ): List<String> = buildList {
    if (mode.isNotBlank()) {
      add(mode)
    }
    add("--exit-code")
    add("--color=never")
    if (includes.isNotEmpty()) {
      add("--include=" + includes.joinToString(","))
    }
    if (excludes.isNotEmpty()) {
      add("--exclude=" + excludes.joinToString(","))
    }
    addAll(additionalOptions)
    // jardiff positional arguments: <left> <right>.
    // The reference (the artifact validated by the build job) is the left/baseline side,
    // the freshly built candidate is the right side.
    add(reference.absolutePath)
    add(candidate.absolutePath)
  }

  /** Maps a jardiff process exit value to an [Outcome]. */
  fun outcomeOf(exitValue: Int): Outcome = when (exitValue) {
    0 -> Outcome.IDENTICAL
    1 -> Outcome.DIFFERENT
    else -> Outcome.ERROR
  }

  /**
   * Renders the equivalent `java -cp … <mainClass> <arguments>` shell command, so the comparison
   * can be copy-pasted and reproduced outside Gradle. Tokens containing shell-significant
   * characters (spaces, globs, commas, ...) are single-quoted.
   */
  fun shellCommandLine(classpath: Iterable<File>, mainClass: String, arguments: List<String>): String {
    val classpathValue = classpath.joinToString(File.pathSeparator) { it.absolutePath }
    return (listOf("java", "-cp", classpathValue, mainClass) + arguments)
      .joinToString(" ", transform = ::shellQuote)
  }

  private fun shellQuote(token: String): String =
    if (token.isNotEmpty() && token.all { it.isLetterOrDigit() || it in "/._-=:" }) {
      token
    } else {
      "'" + token.replace("'", "'\\''") + "'"
    }
}
