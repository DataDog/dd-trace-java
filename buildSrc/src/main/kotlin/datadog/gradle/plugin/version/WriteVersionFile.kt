package datadog.gradle.plugin.version

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
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
  val ci: Property<Boolean> = objects.property<Boolean>()
    .convention(
      providerFactory.environmentVariable("CI")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(false)
    )

  @get:Input
  val gitHash: Property<String> = objects.property<String>()
    .convention(
      ci.flatMap { isCi ->
        if (isCi) gitHashProvider(providerFactory, layout) else providerFactory.provider { "dev" }
      }
    )

  @get:OutputDirectory
  val outputDirectory: DirectoryProperty = objects.directoryProperty()
    .convention(layout.buildDirectory.dir("generated/version"))

  @TaskAction
  fun writeVersionFile() {
    val versionFile = outputDirectory.file("${project.name}.version").get().asFile
    versionFile.parentFile.mkdirs()
    versionFile.writeText(versionFileContent())
  }

  private fun versionFileContent(): String =
    if (ci.get()) {
      "${version.get()}~${gitHash.get()}"
    } else {
      localDevelopmentVersion(version.get())
    }

  private fun gitHashProvider(
    providerFactory: ProviderFactory,
    layout: ProjectLayout,
  ): Provider<String> =
    providerFactory.of(GitCommandValueSource::class.java) {
      parameters {
        gitCommand.addAll("git", "rev-parse", "--short", "HEAD")
        workingDirectory.set(layout.projectDirectory)
      }
    }

  private fun localDevelopmentVersion(version: String): String {
    // Project versions are produced by TracerVersionPlugin, so they are either
    // plain release versions or already-qualified snapshot/pre-release versions.
    val trimmedVersion = version.trim()
    return if ('-' in trimmedVersion) {
      "$trimmedVersion.dev"
    } else {
      "$trimmedVersion-SNAPSHOT.dev"
    }
  }
}
