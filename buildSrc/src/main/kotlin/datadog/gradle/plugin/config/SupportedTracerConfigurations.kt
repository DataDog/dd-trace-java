package datadog.gradle.plugin.config

import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

open class SupportedTracerConfigurations @Inject constructor(objects: ObjectFactory, layout: ProjectLayout) {
  val configOwnerPath = objects.property<String>(String::class.java).convention(":utils:config-utils")
  val className = objects.property<String>(String::class.java).convention("datadog.config.GeneratedSupportedConfigurations")

  val jsonFile = objects.fileProperty().convention(layout.projectDirectory.file("src/main/resources/supported-configurations.json"))

  val destinationDirectory = objects.directoryProperty().convention(layout.buildDirectory.dir("generated/supportedConfigurations"))

  // ... other configs
  // maybe excluded files in specific submodules like "internal-api/...", "dd-java-agent/..."
}
