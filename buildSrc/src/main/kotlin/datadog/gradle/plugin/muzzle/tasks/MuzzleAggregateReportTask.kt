package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils
import datadog.gradle.plugin.muzzle.TestedArtifact
import org.eclipse.aether.util.version.GenericVersionScheme
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.TreeMap

abstract class MuzzleAggregateReportTask : AbstractMuzzleReportTask() {
  init {
    description = "Aggregate instrumentation dependency range reports"
    versionsFile.convention(project.layout.buildDirectory.file("$MUZZLE_DEPS_RESULTS/muzzle.csv"))
    // Preserve the existing order-sensitive handling of Maven-equivalent version spellings.
    outputs.upToDateWhen { false }
  }

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val versionReports: ConfigurableFileCollection

  /**
   * Merges the declared muzzle report artifacts into a single CSV.
   */
  @TaskAction
  fun mergeReports() {
    val map = TreeMap<String, TestedArtifact>()
    val versionScheme = GenericVersionScheme()
    versionReports.forEach {
      logger.info("Processing muzzle report: $it")
      it.useLines { lines ->
        lines.forEachIndexed { idx, line ->
          if (idx == 0) return@forEachIndexed // skip header
          val split = line.split(",")
          val parsed = TestedArtifact(
            split[0],
            split[1],
            split[2],
            versionScheme.parseVersion(split[3]),
            versionScheme.parseVersion(split[4])
          )
          map.merge(parsed.key(), parsed) { x, y ->
            TestedArtifact(
              x.instrumentation,
              x.group,
              x.module,
              MuzzleMavenRepoUtils.lowest(x.lowVersion, y.lowVersion),
              MuzzleMavenRepoUtils.highest(x.highVersion, y.highVersion)
            )
          }
        }
      }
    }
    dumpVersionsToCsv(map)
  }
}
