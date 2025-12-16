package datadog.gradle.plugin.naming

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Extension for configuring instrumentation naming convention checks.
 *
 * Example usage:
 * ```
 * instrumentationNaming {
 *   instrumentationsDir.set(file("dd-java-agent/instrumentation"))
 *   exclusions.set(listOf("http-url-connection", "sslsocket"))
 *   suffixes.set(listOf("-common", "-stubs""))
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
   * List of module names to exclude from naming convention checks.
   * These modules will not be validated against the naming rules.
   */
  abstract val exclusions: ListProperty<String>

  /**
   * List of allowed suffixes for module names (e.g., "-common", "-stubs").
   * Module names must end with either one of these suffixes or a version number.
   * Defaults to ["-common", "-stubs"].
   */
  abstract val suffixes: ListProperty<String>

  init {
    instrumentationsDir.convention("dd-java-agent/instrumentation")
    exclusions.convention(emptyList())
    suffixes.convention(listOf("-common", "-stubs"))
  }
}
