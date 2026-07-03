package datadog.gradle.plugin.jardiff

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations

/**
 * Compares a freshly built jar against a reference jar using the
 * [jardiff](https://github.com/bric3/jardiff) CLI and fails the build if they differ.
 *
 * This guards Maven Central publication against non-deterministic rebuilds: the artifact about to
 * be published (that maybe rebuilt with faults in a later job) is compared
 * against a reference artifact.
 *
 * The reference jar is resolved, in order of precedence, from:
 * 1. the `--reference-jar=<path>` command-line option (handy to compare a single artifact locally
 *    before publishing), then
 * 2. the [referenceJar] property (wired by the `dd-trace-java.jardiff` plugin from the
 *    `-PjardiffReferenceDir` project property by matching the built jar's file name in that
 *    directory).
 *
 * The comparison is mandatory: once the task runs, a missing reference fails the build, so a
 * dropped CI property or a forgotten `--reference-jar` cannot silently publish an unverified jar.
 * Modules that have no reference artifact to compare can disable the task instead
 * (`compareToReferenceJar { enabled = false }`).
 */
abstract class JardiffTask @Inject constructor(
  private val execOperations: ExecOperations,
) : DefaultTask() {

  /** The freshly built jar to validate (the main publication artifact). */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val candidateJar: RegularFileProperty

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
    description = "Path to the reference jar to compare the built jar against " +
      "(typically the artifact produced by the CI `build` job).",
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

  /** Destination of the captured jardiff report. */
  @get:OutputFile
  abstract val reportFile: RegularFileProperty

  init {
    includes.convention(emptyList())
    excludes.convention(emptyList())
    // This task is a publication gate, and as such must never be skipped as "up-to-date":
    // i.e. always re-run so a divergent rebuild cannot slip through on a stale execution history.
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun compare() {
    val reference = resolveReferenceJar()
      ?: throw GradleException(
        "No reference jar configured to compare the built jar against.\n" +
          "Provide one via --reference-jar=<path> or -PjardiffReferenceDir=<dir> (the directory " +
          "holding the `build` job artifacts), or disable this task for modules with no reference.",
      )
    if (!reference.isFile) {
      throw GradleException(
        "Reference jar does not exist: ${reference.absolutePath}\n" +
          "Pass an existing jar via --reference-jar=<path> or point -PjardiffReferenceDir at the " +
          "directory holding the `build` job artifacts.",
      )
    }
    val candidate = candidateJar.get().asFile

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
    val reportDestination = reportFile.get().asFile
    reportDestination.parentFile?.mkdirs()
    reportDestination.writeText(report)

    when (JardiffComparison.outcomeOf(execResult.exitValue)) {
      JardiffComparison.Outcome.IDENTICAL ->
        logger.lifecycle("✓ ${candidate.name} is identical to the reference jar ${reference.name}")

      JardiffComparison.Outcome.DIFFERENT ->
        throw GradleException(
          buildString {
            appendLine("Built jar differs from the reference jar, refusing to publish.")
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
            "${candidate.name} against ${reference.name}. Output:\n" +
            report.ifBlank { "(no output captured)" },
        )
    }
  }

  private fun resolveReferenceJar(): File? {
    referenceJarPath.orNull?.takeIf { it.isNotBlank() }?.let { return File(it) }
    if (referenceJar.isPresent) {
      return referenceJar.get().asFile
    }
    return null
  }
}
