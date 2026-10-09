package datadog.buildlogic.tagRegistry

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

/** Generates the tag registry and adds its Java output to the main source set. */
class TagRegistryGeneratorPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val ext = project.extensions.create<TagRegistryExtension>("tagRegistry")
    val generate =
      project.tasks.register<GenerateKnownTagsTask>("generateKnownTags") {
        group = "build"
        description = "Generates the Java tag registry and assignment reports."
        tagConventionsFile.convention(ext.tagConventionsFile)
        destinationDirectory.convention(ext.destinationDirectory)
      }
    project.pluginManager.withPlugin("java") {
      project.extensions.configure<SourceSetContainer> {
        named("main") {
          java.srcDir(generate.flatMap { it.destinationDirectory.dir("java") })
        }
      }
    }
  }
}
