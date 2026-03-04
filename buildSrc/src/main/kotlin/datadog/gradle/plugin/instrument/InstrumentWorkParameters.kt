package datadog.gradle.plugin.instrument

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkParameters

interface InstrumentWorkParameters : WorkParameters {
  val buildStartedTime: Property<Long>
  val pluginClassPath: ConfigurableFileCollection
  val plugins: ListProperty<String>
  val instrumentingClassPath: ConfigurableFileCollection
  val compilerOutputDirectory: DirectoryProperty
  val tmpDirectory: DirectoryProperty
  val includeClassDirectories: ConfigurableFileCollection
}
