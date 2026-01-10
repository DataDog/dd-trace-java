package datadog.gradle.plugin.instrument

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty

abstract class BuildTimeInstrumentationExtension {
  abstract ListProperty<String> getPlugins()
  abstract ListProperty<DirectoryProperty> getAdditionalClasspath()
}
