package datadog.gradle.plugin.config

import org.gradle.api.Project
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

open class SupportedTracerConfigurations
@Inject
constructor(
  objects: ObjectFactory,
  layout: ProjectLayout,
  project: Project
) {
  val configOwnerPath =
    objects
      .property<String>(String::class.java)
      .convention(":utils:config-utils")

  val className =
    objects
      .property<String>(String::class.java)
      .convention("datadog.trace.config.inversion.GeneratedSupportedConfigurations")

  val jsonFile =
    objects.fileProperty().convention(
      project.rootProject.layout.projectDirectory
        .file("metadata/supported-configurations.json"),
    )

  val destinationDirectory =
    objects.directoryProperty().convention(
      layout.buildDirectory
        .dir("generated/supportedConfigurations"),
    )
}
