package datadog.gradle.plugin.config

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

class SupportedConfigPlugin : Plugin<Project> {
  override fun apply(targetProject: Project) {
    val extension = targetProject.extensions.create("supportedTracerConfigurations", SupportedTracerConfigurations::class.java)
    generateSupportedConfigurations(targetProject, extension)
  }

  private fun generateSupportedConfigurations(
    targetProject: Project,
    extension: SupportedTracerConfigurations
  ) {
    val generateTask =
      targetProject.tasks.register("generateSupportedConfigurations", ParseSupportedConfigurationsTask::class.java) {
        jsonFile.set(extension.jsonFile)
        destinationDirectory.set(extension.destinationDirectory)
        className.set(extension.className)
      }

    val sourceSet = targetProject.extensions.getByType(SourceSetContainer::class.java).named(SourceSet.MAIN_SOURCE_SET_NAME)
    sourceSet.configure {
      java.srcDir(generateTask)
    }

    targetProject.tasks.named("javadoc") {
      dependsOn(generateTask)
    }
  }
}
