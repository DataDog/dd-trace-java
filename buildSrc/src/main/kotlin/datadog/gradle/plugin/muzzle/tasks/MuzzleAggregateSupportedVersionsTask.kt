package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.SupportedVersions
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Merges the instrumentations' supported versions into `supported_versions.json`. */
abstract class MuzzleAggregateSupportedVersionsTask : AbstractMuzzleTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val supportedVersionsReports: ConfigurableFileCollection

  @get:OutputFile
  abstract val supportedVersionsFile: RegularFileProperty

  init {
    description = "Aggregate instrumentation supported versions into supported_versions.json"
  }

  @TaskAction
  fun aggregate() {
    val fragments = supportedVersionsReports.flatMap { SupportedVersions.readFragments(it) }
    val entries = SupportedVersions.entries(SupportedVersions.merge(fragments))
    SupportedVersions.writeEntries(supportedVersionsFile.get().asFile, entries)
    logger.info("Wrote ${entries.size} supported versions entries to\n  ${supportedVersionsFile.get().asFile}")
  }
}
