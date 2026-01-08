package datadog.gradle.plugin.naming

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Extension for configuring instrumentation naming convention checks.
 *
 * Example usage:
 * ```
 * instrumentationNaming {
 *   instrumentationsDir.set(file("dd-java-agent/instrumentation"))
 *   exclusions.set(setOf("http-url-connection", "sslsocket"))
 *   suffixes.set(setOf("-common", "-stubs"))
 * }
 * ```
 */
abstract class InstrumentationNamingExtension {
  /**
   * The directory containing instrumentation modules.
   * Defaults to "dd-java-agent/instrumentation".
   */
  abstract val instrumentationsDir: Property<String>

  /**
   * Set of module names to exclude from naming convention checks.
   * These modules will not be validated against the naming rules.
   */
  abstract val exclusions: SetProperty<String>

  /**
   * Set of allowed suffixes for module names (e.g., "-common", "-stubs").
   * Module names must end with either one of these suffixes or a version number.
   * Defaults to ["-common", "-stubs"].
   */
  abstract val suffixes: SetProperty<String>

  init {
    instrumentationsDir.convention("dd-java-agent/instrumentation")
    exclusions.convention(emptySet())
    suffixes.convention(setOf("-common", "-stubs"))
  }
}
