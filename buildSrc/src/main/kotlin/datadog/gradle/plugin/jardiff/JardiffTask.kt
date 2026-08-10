package datadog.gradle.plugin.jardiff

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations

/**
 * Compares a candidate jar against a reference jar using the
 * [jardiff](https://github.com/bric3/jardiff) CLI and fails the build if they differ.
 *
 * The same task class supports two plugin-configured modes:
 * - `compareToReferenceJar` wires [candidateJar] to the project's archive output, so Gradle builds
 *   that archive before comparing it.
 * - `compareJarFiles` leaves [candidateJar] unset and expects `--candidate-jar=<path>`, so it can
 *   compare an already-produced artifact without depending on `jar` or `shadowJar`.
 *
 * The reference jar is resolved, in order of precedence, from:
 * 1. the `--reference-jar=<path>` command-line option, then
 * 2. the [referenceJar] property (wired by the `dd-trace-java.jardiff` plugin from the
 *    `-PjardiffReferenceDir` project property by matching the built jar's file name in that
 *    directory).
 */
abstract class JardiffTask @Inject constructor(
  private val execOperations: ExecOperations,
) : DefaultTask() {

  /**
   * The project archive to validate. Optional so the same task class can also compare explicit
   * file paths without depending on the archive-producing task.
   */
  @get:InputFile
  @get:Optional
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val candidateJar: RegularFileProperty

  /**
   * Command-line override for the candidate jar path; takes precedence over [candidateJar].
   * A relative path is resolved against the current working directory (the Gradle invocation
   * directory), matching how CLI users expect paths to behave.
   */
  @get:Input
  @get:Optional
  @get:Option(
    option = "candidate-jar",
    description = "Path to the candidate jar to compare against the reference jar.",
  )
  abstract val candidateJarPath: Property<String>

  /**
   * The reference jar to compare against. Optional; usually wired from `-PjardiffReferenceDir`.
   * Kept [Internal] (the task always re-runs, see the `upToDateWhen { false }` below) so a missing
   * reference produces a clear error message rather than a generic input-validation failure.
   */
  @get:Internal
  abstract val referenceJar: RegularFileProperty

  /**
   * Command-line override for the reference jar path; takes precedence over [referenceJar].
   * A relative path is resolved against the current working directory (the Gradle invocation
   * directory), matching how CLI users expect paths to behave.
   */
  @get:Input
  @get:Optional
  @get:Option(
    option = "reference-jar",
    description = "Path to the reference jar to compare the candidate jar against.",
  )
  abstract val referenceJarPath: Property<String>

  /** Runtime classpath hosting the jardiff CLI ([mainClass]). */
  @get:Classpath
  abstract val jardiffClasspath: ConfigurableFileCollection

  /** Glob patterns of entries to include in the comparison; empty means compare everything. */
  @get:Input
  abstract val includes: ListProperty<String>

  /** Glob patterns of entries to exclude from the comparison. */
  @get:Input
  abstract val excludes: ListProperty<String>

  /**
   * jardiff output mode flag (e.g. `--stat`, `--status`); blank selects the default full diff.
   * Defaulted by the plugin; overridable on the command line.
   */
  @get:Input
  @get:Option(
    option = "mode",
    description = "jardiff output mode flag, e.g. --mode=--status (blank selects the default full " +
      "diff). Overrides the jardiff extension.",
  )
  abstract val mode: Property<String>

  /**
   * Extra jardiff options passed verbatim, right before the two jars (e.g. `--ignore-member-order`).
   * Defaulted by the plugin; overridable on the command line (repeatable).
   */
  @get:Input
  @get:Option(
    option = "jardiff-option",
    description = "Additional jardiff option passed verbatim; repeatable, e.g. " +
      "--jardiff-option=--ignore-member-order. Overrides the jardiff extension.",
  )
  abstract val additionalOptions: ListProperty<String>

  /**
   * When true, tolerate mismatching candidate and reference jar hashes if jardiff reports no
   * differences. Matching hashes still skip the more expensive jardiff process.
   */
  @get:Input
  @get:Option(
    option = "ignore-hash-check",
    description = "Do not fail when jar hashes differ but jardiff detects no differences.",
  )
  abstract val ignoreHashCheck: Property<Boolean>

  /**
   * When true, the `.version` files entries are excluded from the comparison.
   * This is useful in particular as those files can be part of runtimeClasspath normalization
   * which ignores them too. `false` under CI, and true otherwise; overridable on the command line.
   */
  @get:Input
  @get:Option(
    option = "ignore-version-files",
    description = "Exclude **/*.version entries (volatile git-hash stamps) from the comparison.",
  )
  abstract val ignoreVersionFiles: Property<Boolean>

  /** Fully qualified main class of the jardiff CLI (defaulted by the plugin). Overridable for testing. */
  @get:Input
  abstract val mainClass: Property<String>

  /** Directory receiving captured jardiff reports. */
  @get:OutputDirectory
  abstract val reportDir: DirectoryProperty

  /** Optional exact destination for the captured jardiff report. Prefer [reportDir]. */
  @get:Internal
  abstract val reportFile: RegularFileProperty

  init {
    includes.convention(emptyList())
    excludes.convention(emptyList())
    reportDir.convention(project.layout.buildDirectory.dir("reports/jardiff"))
    ignoreHashCheck.convention(false)
    // These comparisons are explicit verification gates and must never be skipped as up-to-date.
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun compare() {
    val reference = resolveReferenceJar()
    val candidate = resolveCandidateJar()
    val reportDestination = reportDestination(candidate)
    val hashesMatch = sameHash(reference, candidate)

    if (hashesMatch) {
      val message = "SHA-256 hashes match for ${candidate.name} and ${reference.name}."
      writeReport(reportDestination, "$message\n")
      logger.info("Skipping jardiff for ${candidate.name} because SHA-256 hashes match.")
      return
    }

    val effectiveExcludes = buildList {
      addAll(excludes.get())
      if (ignoreVersionFiles.get()) {
        add("**/*.version")
      }
    }
    val arguments = JardiffComparison.buildArguments(
      reference = reference,
      candidate = candidate,
      mode = mode.get(),
      includes = includes.get(),
      excludes = effectiveExcludes,
      additionalOptions = additionalOptions.get(),
    )

    val toolClasspath = jardiffClasspath
    val mainClassName = mainClass.get()
    logger.info(
      "jardiff command: {}",
      JardiffComparison.shellCommandLine(toolClasspath.files, mainClassName, arguments),
    )
    val captured = ByteArrayOutputStream()
    val execResult = execOperations.javaexec {
      classpath = toolClasspath
      mainClass.set(mainClassName)
      args(arguments)
      standardOutput = captured
      errorOutput = captured
      isIgnoreExitValue = true
    }

    val report = captured.toString("UTF-8")
    writeReport(reportDestination, report)

    when (JardiffComparison.outcomeOf(execResult.exitValue)) {
      JardiffComparison.Outcome.IDENTICAL -> {
        if (!hashesMatch) {
          val message =
            "SHA-256 hashes differ for ${candidate.name} (candidate) and ${reference.name} (reference), " +
              "but jardiff detected no differences."
          logger.warn(message)
          if (!ignoreHashCheck.get()) {
            throw GradleException(
              buildString {
                appendLine(message)
                appendLine()
                appendLine("  candidate : ${candidate.absolutePath}")
                appendLine("  reference : ${reference.absolutePath}")
                appendLine("  report    : ${reportDestination.absolutePath}")
              },
            )
          }
        }
        logger.lifecycle(
          "Jardiff comparison passed for ${candidate.name} (candidate) against ${reference.name} (reference).",
        )
      }

      JardiffComparison.Outcome.DIFFERENT ->
        throw GradleException(
          buildString {
            appendLine("Candidate jar differs from the reference jar.")
            appendLine("TODO: inspect gradle build scripts")
            appendLine()
            appendLine("  candidate : ${candidate.absolutePath}")
            appendLine("  reference : ${reference.absolutePath}")
            appendLine("  report    : ${reportDestination.absolutePath}")
            appendLine()
            appendLine("jardiff report:")
            append(report.ifBlank { "(no output captured)" })
          },
        )

      JardiffComparison.Outcome.ERROR ->
        throw GradleException(
          "jardiff failed with exit code ${execResult.exitValue} while comparing " +
            "${candidate.name} (candidate) against ${reference.name} (reference)." +
            "Output:\n" +
            report.ifBlank { "(no output captured)" },
        )
    }
  }

  private fun resolveReferenceJar(): File =
    referenceJarPath.orNull?.takeIf { it.isNotBlank() }?.let(::File)?.let {
      requireExistingJar(it, "Reference jar")
    } ?: when {
      referenceJar.isPresent -> requireExistingJar(referenceJar.get().asFile, "Reference jar")
      else -> throw GradleException(
        "No reference jar configured to compare the candidate jar against.\n" +
          "Provide one via --reference-jar=<path> or -PjardiffReferenceDir=<dir> (the directory " +
          "holding the `build` job artifacts), or disable this task for modules with no reference.",
      )
    }

  private fun resolveCandidateJar(): File =
    candidateJarPath.orNull?.takeIf { it.isNotBlank() }?.let(::File)?.let {
      requireExistingJar(it, "Candidate jar")
    } ?: when {
      candidateJar.isPresent -> requireExistingJar(candidateJar.get().asFile, "Candidate jar")
      else -> throw GradleException(
        "No candidate jar configured to compare against the reference jar.\n" +
          "Pass an existing jar via --candidate-jar=<path> or configure the task's candidateJar.",
      )
    }

  private fun requireExistingJar(jar: File, role: String): File {
    if (!jar.isFile) {
      throw GradleException("$role does not exist: ${jar.absolutePath}")
    }
    return jar
  }

  private fun reportDestination(candidate: File): File =
    when {
      reportFile.isPresent -> reportFile.get().asFile
      else -> reportDir.file("${candidate.name}.txt").get().asFile
    }

  private fun sameHash(left: File, right: File): Boolean =
    left.length() == right.length() && sha256(left).contentEquals(sha256(right))

  private fun sha256(file: File): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) {
          break
        }
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest()
  }

  private fun writeReport(reportDestination: File, report: String) {
    reportDestination.parentFile?.mkdirs()
    reportDestination.writeText(report)
  }
}
