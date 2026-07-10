package datadog.gradle.plugin.version

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

abstract class WriteVersionFile @Inject constructor(
  providerFactory: ProviderFactory,
  layout: ProjectLayout,
  objects: ObjectFactory,
) : DefaultTask() {

  @get:Input
  val version: Property<String> = objects.property<String>()
    .convention(providerFactory.provider { project.version.toString() })

  @get:Input
  val gitHash: Property<String> = objects.property<String>()
    .convention(
      providerFactory.of(GitCommandValueSource::class.java) {
        parameters {
          gitCommand.addAll("git", "rev-parse", "--short", "HEAD")
          workingDirectory.set(layout.projectDirectory)
        }
      }
    )

  @get:OutputDirectory
  val outputDirectory: DirectoryProperty = objects.directoryProperty()
    .convention(layout.buildDirectory.dir("generated/version"))

  @TaskAction
  fun writeVersionFile() {
    val versionFile = outputDirectory.file("${project.name}.version").get().asFile
    versionFile.parentFile.mkdirs()
    versionFile.writeText("${version.get()}~${gitHash.get()}")
  }
}
