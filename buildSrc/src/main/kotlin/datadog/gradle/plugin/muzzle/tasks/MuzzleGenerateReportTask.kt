package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.MuzzleMavenRepoUtils
import datadog.gradle.plugin.muzzle.TestedArtifact
import org.eclipse.aether.util.version.GenericVersionScheme
import org.gradle.api.tasks.TaskAction
import java.util.TreeMap

abstract class MuzzleGenerateReportTask : AbstractMuzzleReportTask() {
  init {
    description = "Print instrumentation version report"
  }

  private val versionReports = project.fileTree(project.rootProject.layout.buildDirectory.dir(MUZZLE_DEPS_RESULTS)) {
    include("*.csv")
  }

  /**
   * Merges all muzzle report CSVs in the build directory into a single map and writes the merged results to a CSV.
   */
  @TaskAction
  fun mergeReports() {
    val map = TreeMap<String, TestedArtifact>()
    val versionScheme = GenericVersionScheme()
    versionReports.forEach {
      project.logger.info("Processing muzzle report: $it")
      it.useLines { lines ->
        lines.forEachIndexed { idx, line ->
          if (idx == 0) return@forEachIndexed // skip header
          val split = line.split(",")
          val parsed =
            TestedArtifact(
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
