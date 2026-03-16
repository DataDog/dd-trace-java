package datadog.gradle.plugin.instrument

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty

/**
 * Extension for configuring the build-time instrumentation plugin.
 *
 * Exposed as `buildTimeInstrumentation { ... }`.
 *
 * @see BuildTimeInstrumentationPlugin
 */
abstract class BuildTimeInstrumentationExtension {
  /**
   * Fully qualified ByteBuddy plugin class names to apply during post-compilation instrumentation.
   *
   * Each plugin class must implement [net.bytebuddy.build.Plugin] and provide a constructor
   * accepting a [java.io.File] target directory.
   */
  abstract val plugins: ListProperty<String>

  /**
   * Additional classpath entries required to resolve instrumentation plugins and their dependencies.
   */
  abstract val additionalClasspath: ListProperty<DirectoryProperty>

  /**
   * Additional class directories to include in instrumentation processing.
   */
  abstract val includeClassDirectories: ConfigurableFileCollection
}
