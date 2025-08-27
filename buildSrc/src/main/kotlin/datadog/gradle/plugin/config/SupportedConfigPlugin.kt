package datadog.gradle.plugin.config

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

class SupportedConfigPlugin : Plugin<Project> {
  override fun apply(targetProject: Project) {
    generateSupportedConfigurations(targetProject)
  }

  private fun generateSupportedConfigurations(targetProject: Project) {
    val generateTask =
      targetProject.tasks.register("generateSupportedConfigurations", ParseSupportedConfigurationsTask::class.java) {
        jsonFile.set(project.file("src/main/resources/supported-configurations.json"))
        destinationDirectory.set(project.layout.buildDirectory.dir("generated/supportedConfigurations"))
        className.set("datadog.config.GeneratedSupportedConfigurations")
      }

    // Ensure Java compilation depends on the generated sources

    val sourceset = targetProject.extensions.getByType(SourceSetContainer::class.java).named(SourceSet.MAIN_SOURCE_SET_NAME)
    sourceset.configure {
      java.srcDir(generateTask)
    }
  }
}
