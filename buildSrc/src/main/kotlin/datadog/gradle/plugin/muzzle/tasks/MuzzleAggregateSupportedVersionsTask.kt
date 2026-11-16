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
    val reports = supportedVersionsReports.map { SupportedVersions.readReport(it) }
    val merged = SupportedVersions.merge(reports.flatMap { it.fragments })
    val entries = SupportedVersions.entries(merged)
    // Another project can test a pair that one project doesn't.
    val untested = reports.flatMap { it.untested }.toSet() - merged.map { it.libraryName to it.integrationName }.toSet()
    if (untested.isNotEmpty()) {
      logger.lifecycle("${untested.size} library and integration pairs have no tested version, so they are not reported (run with --info to list them)")
      untested.sortedWith(compareBy({ it.second }, { it.first })).forEach { (library, integration) ->
        logger.info("  not reported: $library ($integration)")
      }
    }
    SupportedVersions.writeEntries(supportedVersionsFile.get().asFile, entries)
    logger.info("Wrote ${entries.size} supported versions entries to\n  ${supportedVersionsFile.get().asFile}")
  }
}
