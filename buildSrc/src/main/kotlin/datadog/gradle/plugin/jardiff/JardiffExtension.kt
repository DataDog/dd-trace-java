package datadog.gradle.plugin.jardiff

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** Configuration for the [JardiffPlugin]. */
interface JardiffExtension {
  /**
   * Maven coordinate of the jardiff CLI resolved to run the comparison.
   * Defaults to [JardiffPlugin.DEFAULT_TOOL_COORDINATE].
   */
  val toolCoordinate: Property<String>

  /**
   * Fully qualified main class of the jardiff CLI.
   * Defaults to [JardiffPlugin.DEFAULT_MAIN_CLASS].
   */
  val mainClass: Property<String>

  /**
   * jardiff output mode flag, e.g. `--stat` or `--status`; blank selects the default full diff.
   * Defaults to [JardiffPlugin.DEFAULT_MODE].
   */
  val mode: Property<String>

  /**
   * Extra jardiff options passed verbatim, right before the two jars (e.g. `--ignore-member-order`
   * or `--class-text-producer=javap`). Empty by default.
   */
  val additionalOptions: ListProperty<String>

  /**
   * Directory receiving jardiff reports. Defaults to `build/reports/jardiff` in the target project.
   */
  val reportDir: DirectoryProperty

  /**
   * When true, tolerate mismatching candidate and reference jar hashes if jardiff reports no
   * differences. Defaults to false.
   */
  val ignoreHashCheck: Property<Boolean>
}
